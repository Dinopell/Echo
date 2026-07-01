"""
Project Echo — Redis 队列任务消费者

监听 Redis 任务队列，消费控制面推送的 AI 处理任务：
  - echo:tasks:transcribe → 调用 WhisperX 执行语音转写

完整处理流水线：
  BRPOP 消费 → 下载音频 → 转写 → 摘要 → 情感分析
  → 检测情感峰值（>0.8 触发视觉卡片生成）
  → 生成向量嵌入
  → HTTP POST 回调控制面 /api/callback/transcribe-result

失败任务写入 echo:tasks:failed 队列。

架构说明：
  控制面（Java）LPUSH 任务 → Redis 队列 → 本消费者 BRPOP 消费
  处理完成后通过 HTTP 回调控制面更新 Neo4j（数据面不直接写图数据库）
"""

import asyncio
import json
import logging
from datetime import datetime
from typing import Optional

import redis
import httpx

from config import get_settings
from models.schemas import (
    TranscribeTaskMessage,
    TranscribeRequest,
    SummarizeRequest,
    GenerateCardRequest,
    EmbedRequest,
    CallbackPayload,
)

logger = logging.getLogger(__name__)

# 情感峰值触发视觉卡片的阈值
SENTIMENT_PEAK_THRESHOLD = 0.8

# 控制面回调端点
CALLBACK_ENDPOINT = "/api/callback/transcribe-result"

# 失败队列 key
FAILED_QUEUE_KEY = "echo:tasks:failed"


class TaskConsumer:
    """
    Redis 任务队列消费者

    使用 BRPOP（阻塞式弹出）从队列尾部取任务，实现 FIFO 消费。
    每个任务处理完成后，将完整结果 HTTP POST 回调控制面。
    """

    def __init__(self):
        self.settings = get_settings()
        self._running = False
        self._redis_client: Optional[redis.Redis] = None

    @property
    def redis_client(self) -> redis.Redis:
        """延迟初始化 Redis 客户端"""
        if self._redis_client is None:
            self._redis_client = redis.from_url(
                self.settings.redis_url,
                decode_responses=True,
            )
        return self._redis_client

    def stop(self):
        """停止消费者循环"""
        self._running = False
        logger.info("任务消费者停止信号已发送")

    async def start(self):
        """
        启动任务消费循环

        在后台持续监听转写队列（echo:tasks:transcribe），
        超时后继续等待（支持优雅停止）。
        """
        self._running = True
        queues = [self.settings.redis_queue_transcribe]
        logger.info("Redis 任务消费者启动，监听队列: %s", queues)

        while self._running:
            try:
                # BRPOP 阻塞等待任务，超时后重新循环（支持优雅停止）
                result = await asyncio.get_event_loop().run_in_executor(
                    None,
                    lambda: self.redis_client.brpop(
                        queues, timeout=self.settings.redis_brpop_timeout
                    ),
                )

                if result is None:
                    continue  # 超时，继续等待

                queue_name, task_json = result
                logger.info("收到任务: queue=%s", queue_name)

                # 转写任务是唯一的队列类型（摘要等由流水线内联处理）
                await self._handle_transcribe_task(task_json)

            except redis.ConnectionError as e:
                logger.error("Redis 连接失败: %s，5 秒后重试...", e)
                await asyncio.sleep(5)
            except asyncio.CancelledError:
                logger.info("任务消费者协程被取消")
                break
            except Exception as e:
                logger.exception("任务处理异常: %s", e)
                await asyncio.sleep(1)  # 防止异常紧循环

        logger.info("Redis 任务消费者已停止")

    # ─────────────────────────────────────────────────────────────
    # 核心流水线
    # ─────────────────────────────────────────────────────────────

    async def _handle_transcribe_task(self, task_json: str):
        """
        处理转写任务——完整 AI 处理流水线

        流程：
        1. 解析任务 JSON
        2. 下载音频 + WhisperX 转写
        3. LLM 摘要 + 情感分析 + 话题提取
        4. 情感峰值检测 → 触发视觉卡片生成
        5. 向量嵌入
        6. HTTP POST 回调控制面
        7. 失败时写入 echo:tasks:failed
        """
        # 解析任务消息
        try:
            task_data = json.loads(task_json)
            task = TranscribeTaskMessage.model_validate(task_data)
        except Exception as e:
            logger.error("任务反序列化失败: %s\n原始数据: %s", e, task_json[:200])
            await self._push_failed_task(task_json, f"任务解析失败: {e}")
            return

        task_id = task.task_id
        conversation_id = task.conversation_id
        logger.info("开始处理转写任务: task_id=%s, conversation_id=%s",
                    task_id, conversation_id)

        # 更新任务状态为 processing
        self._update_task_status(task_id, "processing")

        # 构建回调载荷（逐步填充）
        payload = CallbackPayload(
            task_id=task_id,
            conversation_id=conversation_id,
            status="processing",
        )

        try:
            # ── 步骤 1：语音转写 ──────────────────────────────────
            transcript, segments_data, speakers_data, language, duration = \
                await self._step_transcribe(task)
            payload.transcript = transcript
            payload.segments = segments_data
            payload.speakers = speakers_data
            payload.language = language
            payload.duration_seconds = duration
            # 隐私：不记录转写内容明文
            logger.info("转写完成: task_id=%s, 片段数=%d", task_id, len(segments_data or []))

            # ── 步骤 2：LLM 摘要 + 情感分析 + 话题提取 ──────────
            summary, key_persons, key_topics, sentiment, sentiment_score = \
                await self._step_summarize(conversation_id, transcript, task_id)
            payload.summary = summary
            payload.key_persons = key_persons
            payload.key_topics = key_topics
            payload.sentiment = sentiment
            payload.sentiment_score = sentiment_score
            logger.info("摘要完成: task_id=%s, 情感=%s(%.2f)", task_id, sentiment, sentiment_score)

            # ── 步骤 3：情感峰值检测 → 视觉卡片 ────────────────
            if abs(sentiment_score) >= SENTIMENT_PEAK_THRESHOLD:
                logger.info("情感峰值触发视觉卡片生成: score=%.2f", sentiment_score)
                card_key, card_url, sd_prompt = await self._step_generate_card(
                    conversation_id, summary, sentiment_score
                )
                payload.card_object_key = card_key
                payload.card_url = card_url
                logger.info("视觉卡片生成完成: key=%s", card_key)

            # ── 步骤 4：向量嵌入 ────────────────────────────────
            embedding = await self._step_embed(summary or transcript[:500])
            payload.embedding = embedding
            logger.info("向量嵌入完成: 维度=%d", len(embedding) if embedding else 0)

            # ── 步骤 5：更新状态并回调控制面 ────────────────────
            payload.status = "done"
            if payload.key_persons:
                payload.participants = ",".join(payload.key_persons)
            self._update_task_status(task_id, "done", {
                "transcript_length": len(transcript),
                "sentiment": sentiment,
                "sentiment_score": str(sentiment_score),
            })
            asyncio.create_task(self._notify_control_plane(payload))
            logger.info("转写任务流水线完成: task_id=%s", task_id)

        except Exception as e:
            logger.exception("转写任务流水线失败: task_id=%s", task_id)
            payload.status = "failed"
            payload.error_message = str(e)
            self._update_task_status(task_id, "failed", {"error": str(e)})

            # 写入失败队列
            await self._push_failed_task(task_json, str(e))

            # 回调控制面（告知失败）
            asyncio.create_task(self._notify_control_plane(payload))

    # ─────────────────────────────────────────────────────────────
    # 流水线各步骤实现
    # ─────────────────────────────────────────────────────────────

    async def _step_transcribe(self, task: TranscribeTaskMessage):
        """步骤 1：语音转写"""
        from services.whisperx_service import WhisperXService

        service = WhisperXService()
        request = TranscribeRequest(
            audio_object_key=task.audio_object_key,
            bucket=task.bucket,
            language=task.language,
            conversation_id=task.conversation_id,
            task_id=task.task_id,
            enable_diarization=False,  # 队列任务默认不启用分离（加速处理）
        )
        response = await service.transcribe(request)

        segments_data = [
            {
                "start": seg.start,
                "end": seg.end,
                "text": seg.text,
                "speaker": seg.speaker,
            }
            for seg in response.segments
        ]
        speakers_data = [
            {
                "speaker_id": spk.speaker_id,
                "total_speaking_time": spk.total_speaking_time,
                "segment_count": spk.segment_count,
            }
            for spk in response.speakers
        ]

        return (
            response.transcript,
            segments_data,
            speakers_data,
            response.language,
            response.duration_seconds,
        )

    async def _step_summarize(
        self, conversation_id: str, transcript: str, task_id: str
    ):
        """步骤 2：LLM 摘要 + 情感分析 + 话题提取"""
        from services.llm_service import LLMService

        service = LLMService()
        request = SummarizeRequest(
            conversation_id=conversation_id,
            text=transcript,
            task_id=task_id,
        )
        response = await service.summarize(request)

        return (
            response.summary,
            response.key_persons,
            response.key_topics,
            response.sentiment or "neutral",
            response.sentiment_score,
        )

    async def _step_generate_card(
        self, conversation_id: str, summary: str, sentiment_score: float
    ):
        """步骤 3：生成视觉卡片"""
        from services.image_service import ImageService

        service = ImageService()
        request = GenerateCardRequest(
            conversation_id=conversation_id,
            summary=summary,
            sentiment_score=sentiment_score,
            style="ambient",  # 队列任务默认使用 ambient 风格
        )
        response = await asyncio.get_event_loop().run_in_executor(
            None, service.generate_memory_card, request
        )
        return response.card_object_key, response.card_url, response.sd_prompt

    async def _step_embed(self, text: str) -> list[float]:
        """步骤 4：向量嵌入"""
        from services.embedding_service import EmbeddingService

        service = EmbeddingService()
        try:
            embedding = await service.embed_text(text)
            return embedding
        except Exception as e:
            logger.warning("向量嵌入失败，跳过: %s", e)
            return []

    # ─────────────────────────────────────────────────────────────
    # 工具方法
    # ─────────────────────────────────────────────────────────────

    async def _push_failed_task(self, task_json: str, error_message: str):
        """将失败任务写入 echo:tasks:failed 队列"""
        try:
            failed_data = {
                "original_task": task_json,
                "error": error_message,
                "failed_at": datetime.now().isoformat(),
            }
            await asyncio.get_event_loop().run_in_executor(
                None,
                lambda: self.redis_client.lpush(
                    FAILED_QUEUE_KEY,
                    json.dumps(failed_data, ensure_ascii=False),
                ),
            )
            logger.warning("失败任务已写入队列: %s", FAILED_QUEUE_KEY)
        except Exception as e:
            logger.error("写入失败队列出错: %s", e)

    def _update_task_status(
        self, task_id: str, status: str, extra: Optional[dict] = None
    ):
        """
        更新 Redis 中的任务状态

        Args:
            task_id: 任务 ID
            status: 新状态（pending/processing/done/failed）
            extra: 额外字段（如转写文本长度、错误信息）
        """
        try:
            key = f"{self.settings.redis_result_prefix}{task_id}"
            data = {"status": status, "task_id": task_id}
            if extra:
                data.update(extra)
            self.redis_client.hset(key, mapping=data)
            self.redis_client.expire(key, 86400)  # 24 小时过期
        except Exception as e:
            logger.warning("更新任务状态失败: task_id=%s, error=%s", task_id, e)

    async def _notify_control_plane(self, payload: CallbackPayload):
        """
        通过 HTTP POST 回调控制面，更新 Neo4j 中的对话节点

        数据面不直接写 Neo4j，通过此回调通知控制面处理。

        Args:
            payload: 完整的回调数据
        """
        url = f"{self.settings.control_plane_base_url}{CALLBACK_ENDPOINT}"
        try:
            payload_dict = payload.model_dump(exclude_none=True, by_alias=True, mode="json")
            # 隐私：不记录摘要内容明文到日志
            logger.debug(
                "回调控制面: conversation_id=%s, status=%s",
                payload.conversation_id,
                payload.status,
            )
            async with httpx.AsyncClient(timeout=15.0) as client:
                response = await client.post(url, json=payload_dict)
                if response.status_code in (200, 201, 202):
                    logger.info(
                        "控制面回调成功: conversation_id=%s",
                        payload.conversation_id,
                    )
                else:
                    logger.warning(
                        "控制面回调失败: conversation_id=%s, status=%d, body=%s",
                        payload.conversation_id,
                        response.status_code,
                        response.text[:200],
                    )
        except httpx.ConnectError:
            logger.error(
                "控制面连接失败（服务可能未启动）: %s", url
            )
        except Exception as e:
            logger.error(
                "控制面回调异常: conversation_id=%s, error=%s",
                payload.conversation_id, e,
            )
