"""
Project Echo — 向量嵌入服务

使用本地 Sentence Transformers 模型（BAAI/bge-small-zh-v1.5）生成中文向量嵌入。
模型在首次调用时从 HuggingFace 缓存目录加载（离线模式）。

功能：
- embed_text(): 单文本嵌入
- embed_batch(): 批量嵌入
- 模型懒加载 + 实例缓存，避免重复加载
"""

import asyncio
import logging
from typing import Optional, List

from config import get_settings
from models.schemas import EmbedRequest, EmbedResponse

logger = logging.getLogger(__name__)

# 全局模型实例（延迟加载）
_embedding_model = None
_current_model_name: Optional[str] = None


def _load_embedding_model(model_name: str):
    """
    加载 Sentence Transformers 嵌入模型

    首次调用会从 HuggingFace 缓存加载，后续复用同一实例。
    如果指定了不同的模型名，会重新加载。
    """
    global _embedding_model, _current_model_name

    # 模型名未变且已加载，直接复用
    if _embedding_model is not None and _current_model_name == model_name:
        return _embedding_model

    try:
        from sentence_transformers import SentenceTransformer

        logger.info("加载嵌入模型: %s", model_name)
        _embedding_model = SentenceTransformer(model_name)
        _current_model_name = model_name
        logger.info("嵌入模型加载完成: %s", model_name)
    except ImportError:
        logger.warning("sentence-transformers 未安装，嵌入功能不可用")
        _embedding_model = None
    except Exception as e:
        logger.error("加载嵌入模型失败: %s", e)
        _embedding_model = None

    return _embedding_model


class EmbeddingService:
    """
    向量嵌入服务

    负责将文本转换为稠密向量，用于：
    - 语义相似度搜索
    - 记忆召回
    - 知识图谱节点向量化
    """

    def __init__(self):
        self.settings = get_settings()

    async def embed(self, request: EmbedRequest) -> EmbedResponse:
        """
        生成文本向量嵌入（批量处理）

        Args:
            request: 嵌入请求（含文本列表）

        Returns:
            EmbedResponse: 向量列表
        """
        model_name = request.model or self.settings.embedding_model
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None, self._embed_sync, request.texts, model_name
        )
        return result

    async def embed_text(self, text: str, model_name: Optional[str] = None) -> List[float]:
        """
        单文本嵌入

        Args:
            text: 待嵌入文本
            model_name: 模型名（可选）

        Returns:
            List[float]: 嵌入向量
        """
        name = model_name or self.settings.embedding_model
        loop = asyncio.get_event_loop()
        vectors = await loop.run_in_executor(
            None, self._embed_sync, [text], name
        )
        return vectors.embeddings[0] if vectors.embeddings else []

    async def embed_batch(
        self, texts: List[str], model_name: Optional[str] = None
    ) -> List[List[float]]:
        """
        批量嵌入

        Args:
            texts: 待嵌入文本列表
            model_name: 模型名（可选）

        Returns:
            List[List[float]]: 嵌入向量列表
        """
        name = model_name or self.settings.embedding_model
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None, self._embed_sync, texts, name
        )
        return result.embeddings

    def _embed_sync(self, texts: list[str], model_name: str) -> EmbedResponse:
        """同步嵌入实现（在线程池中运行）"""
        model = _load_embedding_model(model_name)

        if model is None:
            # 返回零向量作为降级处理
            logger.warning("嵌入模型不可用，返回零向量")
            dim = self.settings.embedding_dimension
            embeddings = [[0.0] * dim for _ in texts]
            return EmbedResponse(
                embeddings=embeddings,
                model=model_name,
                dimension=dim,
            )

        try:
            # 执行嵌入（批处理，归一化向量）
            logger.debug("执行嵌入: 文本数=%d", len(texts))
            vectors = model.encode(
                texts,
                normalize_embeddings=True,
                batch_size=32,
                show_progress_bar=False,
            )
            embeddings = vectors.tolist()

            return EmbedResponse(
                embeddings=embeddings,
                model=model_name,
                dimension=len(embeddings[0]) if embeddings else self.settings.embedding_dimension,
            )
        except Exception as e:
            logger.error("嵌入执行失败: %s", e)
            raise
