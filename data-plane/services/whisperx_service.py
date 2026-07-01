"""
Project Echo — WhisperX 语音转写服务

使用本地 WhisperX 模型（基于 faster-whisper）进行语音转写。
所有音频数据在本地处理，不上传至任何云服务。

功能：
- 从 MinIO 下载音频 → VAD 切分 → WhisperX 批量转写
- 强制对齐（词级时间戳）
- 说话人分离
- 支持 MPS backend (Apple Silicon GPU 加速)
"""

import asyncio
import json
import logging
import os
import tempfile
from collections import defaultdict
from typing import Optional

from config import get_settings
from models.schemas import (
    TranscribeRequest,
    TranscribeResponse,
    TranscribeSegment,
    SpeakerInfo,
    WordTimestamp,
)

logger = logging.getLogger(__name__)

# 全局 WhisperX 模型实例（延迟加载，避免启动时占用大量内存）
_whisper_model = None
_model_lock = asyncio.Lock()


def _detect_device() -> str:
    """
    自动检测最佳可用设备

    优先级：MPS (Apple Silicon) > CUDA (NVIDIA GPU) > CPU
    """
    settings = get_settings()
    # 如果配置文件已指定设备，则使用配置
    if settings.whisper_device != "cpu":
        return settings.whisper_device

    try:
        import torch
        # 检查 Apple Silicon MPS
        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            logger.info("检测到 Apple Silicon MPS，使用 MPS 设备加速")
            return "mps"
        # 检查 CUDA
        if torch.cuda.is_available():
            logger.info("检测到 CUDA GPU，使用 CUDA 并行加速")
            return "cuda"
    except ImportError:
        pass

    logger.info("使用 CPU 进行转写")
    return "cpu"


def _load_whisper_model():
    """
    加载 WhisperX 模型（同步方法，在线程池中调用）

    首次调用时加载模型，后续复用同一实例，避免重复加载。
    支持 MPS backend（Apple Silicon GPU 加速）。
    """
    global _whisper_model
    if _whisper_model is not None:
        return _whisper_model

    settings = get_settings()
    device = _detect_device()
    # MPS 不支持 int8，自动回落
    compute_type = settings.whisper_compute_type
    if device == "mps" and compute_type == "int8":
        compute_type = "float16"

    try:
        import whisperx

        logger.info(
            "加载 WhisperX 模型: model=%s, device=%s, compute_type=%s",
            settings.whisper_model,
            device,
            compute_type,
        )
        _whisper_model = whisperx.load_model(
            settings.whisper_model,
            device,
            compute_type=compute_type,
        )
        logger.info("WhisperX 模型加载完成")
    except ImportError:
        logger.warning("whisperx 未安装，转写功能不可用。请运行: pip install whisperx")
        _whisper_model = None
    except Exception as e:
        logger.error("加载 WhisperX 模型失败: %s", e)
        _whisper_model = None

    return _whisper_model


class WhisperXService:
    """
    WhisperX 语音转写服务

    负责：
    - 从 MinIO 下载音频文件
    - VAD 切分 + WhisperX 批量转写
    - 强制对齐（词级时间戳）
    - 说话人分离（diarization）
    - 返回结构化转写结果
    """

    def __init__(self):
        self.settings = get_settings()

    async def transcribe(self, request: TranscribeRequest) -> TranscribeResponse:
        """
        执行语音转写（异步入口）

        Args:
            request: 转写请求（含 MinIO 文件路径）

        Returns:
            TranscribeResponse: 转写结果（含词级时间戳 + 说话人标注）
        """
        # 在线程池中执行同步的 IO 和模型推理，避免阻塞事件循环
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(None, self._transcribe_sync, request)
        return result

    def _transcribe_sync(self, request: TranscribeRequest) -> TranscribeResponse:
        """同步转写实现（在线程池中运行）"""
        tmp_path = None
        try:
            # 1. 从 MinIO 下载音频到临时文件
            suffix = self._get_audio_suffix(request.audio_object_key)
            tmp_fd, tmp_path = tempfile.mkstemp(suffix=suffix)
            os.close(tmp_fd)

            logger.info("开始从 MinIO 下载音频: bucket=%s, key=%s",
                        request.bucket, request.audio_object_key)
            self._download_from_minio(request.bucket, request.audio_object_key, tmp_path)

            # 2. 调用 WhisperX 转写（VAD 切分 + 转写 + 对齐 + 说话人分离）
            transcript, segments, speakers, language, duration = self._run_whisperx(
                tmp_path, request
            )

            # 3. 将转写结果 JSON 存储到 MinIO transcriptions bucket
            self._save_transcript_to_minio(request, transcript, segments, language)

            return TranscribeResponse(
                task_id=request.task_id,
                conversation_id=request.conversation_id,
                transcript=transcript,
                segments=segments,
                speakers=speakers,
                language=language,
                duration_seconds=duration,
            )

        except Exception as e:
            logger.error("转写处理失败: task_id=%s, error=%s", request.task_id, e)
            raise
        finally:
            # 清理临时文件
            if tmp_path and os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except OSError:
                    pass

    def _download_from_minio(self, bucket: str, object_key: str, local_path: str):
        """从 MinIO 下载文件到本地路径"""
        from services.minio_service import MinioService
        minio = MinioService()
        minio.download_to_file(bucket, object_key, local_path)
        logger.debug("从 MinIO 下载完成: bucket=%s, key=%s", bucket, object_key)

    def _run_whisperx(
        self, audio_path: str, request: TranscribeRequest
    ) -> tuple[str, list[TranscribeSegment], list[SpeakerInfo], str, Optional[float]]:
        """
        运行完整 WhisperX 流水线：
        VAD 切分 → 转写 → 对齐 → 说话人分离
        """
        model = _load_whisper_model()
        device = _detect_device()

        if model is None:
            logger.warning("WhisperX 模型不可用，返回占位结果")
            return "[转写功能未就绪，请检查 whisperx 安装]", [], [], request.language, None

        import whisperx

        lang = None if request.language == "auto" else request.language

        # 步骤 1：WhisperX 转写（内置 VAD 切分）
        logger.info("开始 WhisperX 转写：batch_size=%d", self.settings.whisper_batch_size)
        result = model.transcribe(
            audio_path,
            batch_size=self.settings.whisper_batch_size,
            language=lang,
        )
        detected_lang = result.get("language", "zh")
        logger.info("转写完成，检测语言: %s，片段数: %d",
                    detected_lang, len(result.get("segments", [])))

        # 步骤 2：强制对齐（词级时间戳）
        try:
            model_a, metadata = whisperx.load_align_model(
                language_code=detected_lang, device=device
            )
            aligned = whisperx.align(
                result["segments"],
                model_a,
                metadata,
                audio_path,
                device,
                return_char_alignments=False,
            )
            logger.info("对齐完成（词级时间戳）")
        except Exception as e:
            logger.warning("对齐失败，使用原始转写结果: %s", e)
            aligned = result

        # 步骤 3：说话人分离（diarization）
        diarized_segments = aligned.get("segments", [])
        if request.enable_diarization and request.hf_token:
            try:
                diarize_model = whisperx.diarize.DiarizationPipeline(
                    use_auth_token=request.hf_token,
                    device=device,
                )
                diarize_segments = diarize_model(audio_path)
                diarized_segments = whisperx.assign_word_speakers(
                    diarize_segments, aligned
                ).get("segments", [])
                logger.info("说话人分离完成")
            except Exception as e:
                logger.warning("说话人分离失败，跳过: %s", e)

        # 构建结果结构
        segments, speakers = self._build_segments_and_speakers(diarized_segments)
        transcript = " ".join(seg.text for seg in segments)

        # 计算音频时长
        duration = segments[-1].end if segments else None

        return transcript, segments, speakers, detected_lang, duration

    def _build_segments_and_speakers(
        self,
        raw_segments: list,
    ) -> tuple[list[TranscribeSegment], list[SpeakerInfo]]:
        """
        将 WhisperX 输出的原始片段转换为 Pydantic 模型，
        同时统计说话人信息。
        """
        segments: list[TranscribeSegment] = []
        speaker_stats: dict[str, dict] = defaultdict(lambda: {"total_time": 0.0, "count": 0})

        for seg in raw_segments:
            start = seg.get("start", 0.0)
            end = seg.get("end", 0.0)
            text = seg.get("text", "").strip()
            speaker = seg.get("speaker", None)

            # 词级时间戳
            words = []
            for w in seg.get("words", []):
                word_start = w.get("start")
                word_end = w.get("end")
                # 跳过无时间戳的词
                if word_start is None or word_end is None:
                    continue
                words.append(WordTimestamp(
                    word=w.get("word", ""),
                    start=word_start,
                    end=word_end,
                    score=w.get("score"),
                ))

            segments.append(TranscribeSegment(
                start=start,
                end=end,
                text=text,
                speaker=speaker,
                words=words,
            ))

            # 统计说话人发言时长
            if speaker:
                speaker_stats[speaker]["total_time"] += (end - start)
                speaker_stats[speaker]["count"] += 1

        speakers = [
            SpeakerInfo(
                speaker_id=spk_id,
                total_speaking_time=round(stats["total_time"], 2),
                segment_count=stats["count"],
            )
            for spk_id, stats in sorted(speaker_stats.items())
        ]

        return segments, speakers

    def _save_transcript_to_minio(
        self,
        request: TranscribeRequest,
        transcript: str,
        segments: list[TranscribeSegment],
        language: str,
    ) -> None:
        """将转写结果 JSON 存储到 MinIO transcriptions bucket"""
        try:
            from services.minio_service import MinioService
            minio = MinioService()

            result_data = {
                "task_id": request.task_id,
                "conversation_id": request.conversation_id,
                "transcript": transcript,
                "language": language,
                "segments": [
                    {
                        "start": seg.start,
                        "end": seg.end,
                        "text": seg.text,
                        "speaker": seg.speaker,
                        "words": [
                            {"word": w.word, "start": w.start, "end": w.end}
                            for w in seg.words
                        ],
                    }
                    for seg in segments
                ],
            }
            json_bytes = json.dumps(result_data, ensure_ascii=False).encode("utf-8")
            object_key = f"{request.conversation_id}/{request.task_id}.json"

            minio.upload_bytes(
                self.settings.minio_bucket_transcriptions,
                object_key,
                json_bytes,
                content_type="application/json",
            )
            logger.debug("转写结果已存储到 MinIO: %s", object_key)
        except Exception as e:
            # 不因存储失败而中断转写流程
            logger.warning("转写结果存储失败（不影响主流程）: %s", e)

    @staticmethod
    def _get_audio_suffix(object_key: str) -> str:
        """从对象键中提取文件后缀（支持 .enc.{ext} 格式）"""
        if ".enc." in object_key:
            ext = object_key.rsplit(".enc.", 1)[-1]
            return f".{ext}"
        ext = os.path.splitext(object_key)[1]
        return ext if ext else ".wav"
