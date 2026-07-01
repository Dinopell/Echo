"""
Project Echo — 向量嵌入路由

API 端点：
  POST /api/v1/embed   — 对文本进行向量嵌入（用于语义搜索）
"""

import logging
from fastapi import APIRouter, HTTPException, Depends

from models.schemas import EmbedRequest, EmbedResponse
from services.embedding_service import EmbeddingService

logger = logging.getLogger(__name__)

router = APIRouter()


def get_embedding_service() -> EmbeddingService:
    """获取向量嵌入服务实例"""
    return EmbeddingService()


@router.post(
    "/embed",
    response_model=EmbedResponse,
    summary="向量嵌入",
    description="使用本地 Sentence Transformers 模型对文本进行向量化，用于语义搜索",
)
async def embed_texts(
    request: EmbedRequest,
    service: EmbeddingService = Depends(get_embedding_service),
) -> EmbedResponse:
    """
    文本向量嵌入

    处理流程：
    1. 使用本地 Sentence Transformers 模型（BAAI/bge-small-zh-v1.5）生成嵌入向量
    2. 返回嵌入向量列表

    Args:
        request: 包含文本列表的请求体
        service: 嵌入服务实例（依赖注入）

    Returns:
        EmbedResponse: 向量嵌入结果
    """
    logger.info("收到嵌入请求: 文本数量=%d", len(request.texts))
    if not request.texts:
        raise HTTPException(status_code=400, detail="texts 不能为空")
    try:
        result = await service.embed(request)
        return result
    except Exception as e:
        logger.exception("向量嵌入失败")
        raise HTTPException(status_code=500, detail=f"向量嵌入失败: {str(e)}")
