"""
Project Echo — Qwen2.5 文本摘要路由

API 端点：
  POST /api/v1/summarize        — 对转写文本生成摘要，提取关键信息
  POST /api/v1/morning-capsule  — 生成晨间胶囊（前一天记忆精华）
"""

import logging
from fastapi import APIRouter, HTTPException, Depends

from models.schemas import (
    SummarizeRequest,
    SummarizeResponse,
    MorningCapsuleRequest,
    MorningCapsuleResponse,
)
from services.llm_service import LLMService

logger = logging.getLogger(__name__)

router = APIRouter()

# LLM 服务单例
_llm_service: LLMService | None = None


def get_llm_service() -> LLMService:
    """获取 LLM 服务实例（进程内单例）"""
    global _llm_service
    if _llm_service is None:
        _llm_service = LLMService()
    return _llm_service


@router.post(
    "/summarize",
    response_model=SummarizeResponse,
    summary="文本摘要",
    description="使用本地 Qwen2.5 模型对对话转写文本生成摘要，提取关键人物、话题和情感分析",
)
async def summarize_text(
    request: SummarizeRequest,
    service: LLMService = Depends(get_llm_service),
) -> SummarizeResponse:
    """
    对话摘要生成

    处理流程：
    1. 构建摘要提示词（含对话文本和上下文）
    2. 调用本地 Ollama（Qwen2.5）生成摘要（JSON 结构化输出）
    3. 解析摘要结果，提取关键人物、话题列表和情感标签
    4. 返回结构化摘要响应

    Args:
        request: 包含对话文本的请求体
        service: LLM 服务实例（依赖注入）

    Returns:
        SummarizeResponse: 摘要结果（含关键人物、话题、情感分析）
    """
    # 隐私优先：不记录对话内容明文
    logger.info("收到摘要请求: conversation_id=%s", request.conversation_id)
    try:
        result = await service.summarize(request)
        logger.info(
            "摘要完成: conversation_id=%s, 情感=%s",
            request.conversation_id,
            result.sentiment,
        )
        return result
    except Exception as e:
        logger.exception("摘要生成失败: conversation_id=%s", request.conversation_id)
        raise HTTPException(status_code=500, detail=f"摘要生成失败: {str(e)}")


@router.post(
    "/morning-capsule",
    response_model=MorningCapsuleResponse,
    summary="晨间胶囊",
    description="汇总前一天的对话摘要，生成每日记忆精华胶囊",
)
async def generate_morning_capsule(
    request: MorningCapsuleRequest,
    service: LLMService = Depends(get_llm_service),
) -> MorningCapsuleResponse:
    """
    生成晨间胶囊

    处理流程：
    1. 汇总指定日期的多段对话摘要
    2. 调用 Qwen2.5 生成当天记忆精华（温暖自然语气）
    3. 提取关键亮点、涉及人物和整体情绪

    Args:
        request: 包含日期和摘要列表的请求体
        service: LLM 服务实例（依赖注入）

    Returns:
        MorningCapsuleResponse: 每日记忆精华
    """
    logger.info("收到晨间胶囊请求: date=%s, 对话数=%d",
                request.date, len(request.summaries))
    try:
        result = await service.generate_morning_capsule(request)
        return result
    except Exception as e:
        logger.exception("晨间胶囊生成失败: date=%s", request.date)
        raise HTTPException(status_code=500, detail=f"晨间胶囊生成失败: {str(e)}")

