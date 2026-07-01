"""
Project Echo — FastAPI 数据面配置管理

使用 pydantic-settings 从环境变量读取配置。
所有配置项都有合理的本地默认值，支持 .env 文件覆盖。
"""

from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache


class Settings(BaseSettings):
    """
    全局配置类

    优先级：环境变量 > .env 文件 > 默认值
    隐私原则：所有服务地址均指向本地或局域网，无任何第三方云服务地址。
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ── 服务基础配置 ─────────────────────────────────────────
    data_plane_host: str = "0.0.0.0"
    data_plane_port: int = 8001
    log_level: str = "INFO"

    # ── Redis 任务队列 ────────────────────────────────────────
    redis_url: str = "redis://localhost:6379"
    redis_queue_transcribe: str = "echo:queue:transcribe"
    redis_queue_summarize: str = "echo:queue:summarize"
    redis_queue_embed: str = "echo:queue:embed"
    redis_result_prefix: str = "echo:result:"
    # BRPOP 超时（秒），0 表示永久阻塞
    redis_brpop_timeout: int = 5

    # ── MinIO 本地对象存储 ────────────────────────────────────
    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = "echo_minio_admin"
    minio_secret_key: str = "echo_minio_secret"
    minio_secure: bool = False
    minio_bucket_raw_audio: str = "raw-audio"
    minio_bucket_transcriptions: str = "transcriptions"
    minio_bucket_memory_cards: str = "memory-cards"
    minio_bucket_snapshots: str = "snapshots"

    # ── Ollama 本地 LLM（仅本机/局域网访问）──────────────────
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5:7b"
    # LLM 生成超时（秒）
    ollama_timeout: int = 120

    # ── WhisperX 本地语音转写 ─────────────────────────────────
    whisper_model: str = "base"
    whisper_device: str = "cpu"
    whisper_compute_type: str = "int8"
    # 批处理大小（影响内存占用）
    whisper_batch_size: int = 8

    # ── 向量嵌入模型（本地 Sentence Transformers）─────────────
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    embedding_dimension: int = 512

    # ── 控制面回调地址 ────────────────────────────────────────
    # 数据面处理完成后，通过此地址通知控制面更新 Neo4j
    control_plane_base_url: str = "http://localhost:8080"

    # ── 音频加密密钥（与控制面 AUDIO_ENCRYPT_KEY 一致）────────
    audio_encrypt_key: str = ""


@lru_cache()
def get_settings() -> Settings:
    """
    获取全局配置单例（使用 lru_cache 确保只初始化一次）

    Returns:
        Settings: 全局配置实例
    """
    return Settings()
