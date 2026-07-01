"""
Project Echo — 记忆卡片生成路由

API 端点：
  POST /api/v1/generate-card   — 接收情感峰值片段 -> 构建 SD prompt
                               -> 生成视觉卡片 -> 存入 MinIO -> 返回预签名 URL
支持风格：artistic（艺术评释）、ambient（氛围场景）、abstract（抄象联结）
"""

import logging
from fastapi import APIRouter, HTTPException, Depends

from models.schemas import GenerateCardRequest, GenerateCardResponse
from services.image_service import ImageService

logger = logging.getLogger(__name__)

router = APIRouter()

# 图像服务单例
_image_service: ImageService | None = None


def get_image_service() -> ImageService:
    """获取图像生成服务实例（进程内单例）"""
    global _image_service
    if _image_service is None:
        _image_service = ImageService()
    return _image_service


@router.post(
    "/generate-card",
    response_model=GenerateCardResponse,
    summary="生成记忆卡片",
    description=(
        "根据情感峰值片段构建 SD prompt，"
        "生成视觉记忆卡片并存入 MinIO memory-cards bucket，"
        "返回 7 天有效预签名 URL。"
    ),
)
async def generate_memory_card(
    request: GenerateCardRequest,
    service: ImageService = Depends(get_image_service),
) -> GenerateCardResponse:
    """
    生成记忆卡片

    处理流程：
    1. 根据情感分数和风格选择构建 Stable Diffusion prompt
    2. 尝试调用本地 ComfyUI API 生成图像
    3. 降级方案：生成精美 SVG 矢量卡片
    4. 上传到 MinIO memory-cards bucket
    5. 返回 7 天有效预签名 URL

    支持风格：
    - artistic：艺术评释（水彩画风格）
    - ambient：氛围场景（自然光影）
    - abstract：抄象联结（彩色图案）

    Args:
        request: 包含对话摘要、情感峰值和风格的请求体
        service: 图像生成服务实例（依赖注入）

    Returns:
        GenerateCardResponse: 生成结果（含 MinIO 路径、预签名 URL 和 SD prompt）
    """
    logger.info(
        "收到记忆卡片生成请求: conversation_id=%s, style=%s",
        request.conversation_id,
        request.style,
    )
    try:
        result = await service.generate_card(request)
        logger.info(
            "记忆卡片生成完成: conversation_id=%s, key=%s",
            request.conversation_id,
            result.card_object_key,
        )
        return result
    except Exception as e:
        logger.exception("记忆卡片生成失败: conversation_id=%s", request.conversation_id)
        raise HTTPException(status_code=500, detail=f"卡片生成失败: {str(e)}")

