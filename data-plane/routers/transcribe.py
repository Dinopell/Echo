"""
Project Echo — WhisperX 语音转写路由

API 端点：
  POST /api/v1/transcribe   — 触发音频转写（从 MinIO 读取音频文件）
返回： segments（含词级时间戳）、speakers（说话人标注）、duration
"""

import logging
from fastapi import APIRouter, HTTPException, Depends
from fastapi.responses import JSONResponse

from models.schemas import TranscribeRequest, TranscribeResponse
from services.whisperx_service import WhisperXService

logger = logging.getLogger(__name__)

router = APIRouter()

# 依赖注入：WhisperX 服务单例
_whisperx_service: WhisperXService | None = None


def get_whisperx_service() -> WhisperXService:
    """获取 WhisperX 服务实例（进程内单例）"""
    global _whisperx_service
    if _whisperx_service is None:
        _whisperx_service = WhisperXService()
    return _whisperx_service


@router.post(
    "/transcribe",
    response_model=TranscribeResponse,
    summary="语音转写",
    description="从 MinIO 读取音频文件，使用本地 WhisperX 模型进行转写。返回带时间戳的转写结果和说话人标注。",
)
async def transcribe_audio(
    request: TranscribeRequest,
    service: WhisperXService = Depends(get_whisperx_service),
) -> TranscribeResponse:
    """
    触发语音转写任务

    处理流程：
    1. 从 MinIO 下载音频文件到临时目录
    2. VAD 切分 + WhisperX 批量转写
    3. 强制对齐（词级时间戳）
    4. 说话人分离（需要 hf_token）
    5. 转写结果存入 MinIO transcriptions bucket
    6. 返回完整转写结果

    Args:
        request: 包含音频文件位置和语言信息的请求体
        service: WhisperX 服务实例（依赖注入）

    Returns:
        TranscribeResponse: 转写结果（含 segments、speakers、duration）
    """
    # 隐私优先：不记录音频内容明文，只记录元数据
    logger.info(
        "收到转写请求: task_id=%s, conversation_id=%s, bucket=%s",
        request.task_id,
        request.conversation_id,
        request.bucket,
    )
    try:
        result = await service.transcribe(request)
        logger.info(
            "转写完成: task_id=%s, 片段数=%d, 语言=%s, 时长=%.1fs",
            request.task_id,
            len(result.segments),
            result.language,
            result.duration_seconds or 0.0,
        )
        return result
    except FileNotFoundError as e:
        logger.error("音频文件不存在: %s", e)
        raise HTTPException(status_code=404, detail=f"音频文件未找到: {str(e)}")
    except Exception as e:
        logger.exception("转写失败: task_id=%s", request.task_id)
        raise HTTPException(status_code=500, detail=f"转写处理失败: {str(e)}")

