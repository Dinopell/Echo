"""
Project Echo — FastAPI 数据面应用入口

职责：
- AI 推理服务（WhisperX 转写、Qwen2.5 摘要、向量嵌入）
- Redis 队列任务消费
- MinIO 文件读写
- 不直接写 Neo4j，通过 HTTP 回调控制面更新图谱

隐私原则：所有 AI 推理在本地执行，不调用任何第三方 AI API。
"""

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from config import get_settings
from routers import transcribe, summarize, generate_card, embed
from workers.task_consumer import TaskConsumer

# 配置日志
settings = get_settings()
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# 全局 TaskConsumer 实例
task_consumer: TaskConsumer | None = None
consumer_task: asyncio.Task | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    应用生命周期管理

    启动时：初始化 MinIO bucket、启动 Redis 队列消费者
    关闭时：优雅停止消费者、释放模型资源
    """
    global task_consumer, consumer_task

    logger.info("Echo 数据面启动中...")

    # 初始化 MinIO bucket
    try:
        from services.minio_service import MinioService
        minio = MinioService()
        minio.init_all_buckets()
    except Exception as e:
        logger.warning("MinIO bucket 初始化失败（服务可能未就绪）: %s", e)

    # 初始化并启动 Redis 队列消费者（后台任务）
    task_consumer = TaskConsumer()
    consumer_task = asyncio.create_task(task_consumer.start())
    logger.info("Redis 队列消费者已启动")

    yield  # 应用运行期间

    # 优雅关闭
    logger.info("Echo 数据面关闭中...")
    if task_consumer:
        task_consumer.stop()
    if consumer_task and not consumer_task.done():
        consumer_task.cancel()
        try:
            await consumer_task
        except asyncio.CancelledError:
            pass
    logger.info("Echo 数据面已关闭")


# 创建 FastAPI 应用
app = FastAPI(
    title="Echo Data Plane",
    description="Project Echo AI 推理数据面 — 本地隐私优先",
    version="0.1.0",
    lifespan=lifespan,
)

# CORS 配置（仅允许本地控制面访问）
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:8080",
        "http://127.0.0.1:8080",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(transcribe.router, prefix="/api/v1", tags=["转写"])
app.include_router(summarize.router, prefix="/api/v1", tags=["摘要"])
app.include_router(generate_card.router, prefix="/api/v1", tags=["记忆卡片"])
app.include_router(embed.router, prefix="/api/v1", tags=["向量嵌入"])



@app.get("/health", tags=["健康检查"])
async def health_check():
    """
    健康检查端点

    Returns:
        dict: 服务状态信息（用于 Docker HEALTHCHECK 和监控）
    """
    return {
        "status": "healthy",
        "service": "echo-data-plane",
        "version": "0.1.0",
        "consumer_running": consumer_task is not None and not consumer_task.done(),
    }


@app.get("/", tags=["根路径"])
async def root():
    """服务根路径，返回服务说明"""
    return {
        "service": "Echo Data Plane",
        "description": "AI 推理服务（本地隐私优先）",
        "endpoints": {
            "health": "/health",
            "docs": "/docs",
            "transcribe": "/api/v1/transcribe",
            "summarize": "/api/v1/summarize",
            "embed": "/api/v1/embed",
            "generate_card": "/api/v1/generate-card",
        },
    }
