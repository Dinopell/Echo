"""
Project Echo — Pydantic 数据模型定义

定义所有 API 请求/响应的数据结构。
使用 Pydantic v2 语法。
"""

from datetime import datetime
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field


# ──────────────────────────────────────────────
# 通用响应模型
# ──────────────────────────────────────────────

class BaseResponse(BaseModel):
    """通用 API 响应基类"""
    status: str = "success"
    message: Optional[str] = None


class ErrorResponse(BaseModel):
    """错误响应模型"""
    status: str = "error"
    error: str
    detail: Optional[str] = None


# ──────────────────────────────────────────────
# 转写相关模型
# ──────────────────────────────────────────────

class TranscribeRequest(BaseModel):
    """语音转写请求模型"""
    # MinIO 中的音频对象键
    audio_object_key: str = Field(..., description="MinIO 中的音频文件路径")
    # 所在 bucket
    bucket: str = Field(default="raw-audio", description="MinIO bucket 名称")
    # 目标语言（auto 自动检测）
    language: str = Field(default="auto", description="语言代码：auto/zh/en")
    # 关联的对话 ID
    conversation_id: str = Field(..., description="Neo4j 中的对话节点 ID")
    # 任务 ID（用于状态回传）
    task_id: str = Field(..., description="Redis 中的任务 ID")
    # 是否启用说话人分离
    enable_diarization: bool = Field(default=True, description="是否启用说话人分离")
    # HuggingFace token（说话人分离需要）
    hf_token: Optional[str] = Field(None, description="HuggingFace Access Token")


class WordTimestamp(BaseModel):
    """词级时间戳"""
    word: str = Field(..., description="单词文本")
    start: float = Field(..., description="开始时间（秒）")
    end: float = Field(..., description="结束时间（秒）")
    score: Optional[float] = Field(None, description="置信度分数")


class TranscribeSegment(BaseModel):
    """转写文本片段（含时间戳）"""
    start: float = Field(..., description="开始时间（秒）")
    end: float = Field(..., description="结束时间（秒）")
    text: str = Field(..., description="转写文本")
    speaker: Optional[str] = Field(None, description="说话人标签（WhisperX diarization）")
    words: List[WordTimestamp] = Field(default_factory=list, description="词级时间戳列表")


class SpeakerInfo(BaseModel):
    """说话人信息"""
    speaker_id: str = Field(..., description="说话人 ID（SPEAKER_00, SPEAKER_01...）")
    total_speaking_time: float = Field(0.0, description="总发言时长（秒）")
    segment_count: int = Field(0, description="发言片段数量")


class TranscribeResponse(BaseResponse):
    """语音转写响应模型"""
    task_id: str
    conversation_id: str
    transcript: str = Field(..., description="完整转写文本")
    segments: List[TranscribeSegment] = Field(default_factory=list)
    speakers: List[SpeakerInfo] = Field(default_factory=list, description="说话人信息列表")
    language: str
    duration_seconds: Optional[float] = None
    processed_at: datetime = Field(default_factory=datetime.now)


# ──────────────────────────────────────────────
# 摘要相关模型
# ──────────────────────────────────────────────

class SummarizeRequest(BaseModel):
    """文本摘要请求模型"""
    conversation_id: str
    text: str = Field(..., description="待摘要的转写文本")
    # 可选：上下文信息（提高摘要质量）
    context: Optional[str] = Field(None, description="额外上下文（如对话背景）")
    task_id: Optional[str] = None


class SentimentScore(BaseModel):
    """情感分析结果"""
    label: str = Field(..., description="情感标签：positive/negative/neutral")
    score: float = Field(..., description="情感分数：-1.0（负面）到 1.0（正面）")
    confidence: float = Field(default=0.0, description="置信度 0-1")


class SummarizeResponse(BaseResponse):
    """文本摘要响应模型"""
    conversation_id: str
    summary: str = Field(..., description="AI 生成的摘要")
    key_persons: List[str] = Field(default_factory=list, description="提及的关键人物")
    key_topics: List[str] = Field(default_factory=list, description="关键话题列表")
    sentiment: Optional[str] = Field(None, description="情感倾向：positive/negative/neutral")
    sentiment_score: float = Field(default=0.0, description="情感分数：-1.0 到 1.0")
    processed_at: datetime = Field(default_factory=datetime.now)


class MorningCapsuleRequest(BaseModel):
    """晨间胶囊请求模型（汇总前一天记忆精华）"""
    date: str = Field(..., description="目标日期，格式 YYYY-MM-DD")
    conversation_ids: List[str] = Field(default_factory=list, description="当天对话 ID 列表")
    summaries: List[str] = Field(default_factory=list, description="对话摘要列表")


class MorningCapsuleResponse(BaseResponse):
    """晨间胶囊响应模型"""
    date: str
    capsule: str = Field(..., description="当天记忆精华总结")
    highlights: List[str] = Field(default_factory=list, description="关键亮点列表")
    people_mentioned: List[str] = Field(default_factory=list, description="提及的人物")
    mood_summary: str = Field(default="neutral", description="当天整体情绪总结")
    processed_at: datetime = Field(default_factory=datetime.now)


# ──────────────────────────────────────────────
# 向量嵌入相关模型
# ──────────────────────────────────────────────

class EmbedRequest(BaseModel):
    """向量嵌入请求模型"""
    texts: List[str] = Field(..., description="待嵌入的文本列表")
    # 可选：指定模型
    model: Optional[str] = None


class EmbedResponse(BaseResponse):
    """向量嵌入响应模型"""
    embeddings: List[List[float]] = Field(..., description="嵌入向量列表")
    model: str
    dimension: int


# ──────────────────────────────────────────────
# 记忆卡片生成相关模型
# ──────────────────────────────────────────────

class GenerateCardRequest(BaseModel):
    """记忆卡片生成请求模型"""
    conversation_id: str
    summary: str = Field(..., description="对话摘要文本（用于生成卡片内容）")
    # 情感峰值片段文本
    peak_segment: Optional[str] = Field(None, description="情感峰值片段文本")
    # 情感分数（影响风格选择）
    sentiment_score: float = Field(default=0.0, description="情感分数 -1.0 到 1.0")
    # 卡片风格：artistic/ambient/abstract
    style: str = Field(default="ambient", description="卡片风格：artistic/ambient/abstract")
    # 卡片风格提示词
    style_hint: Optional[str] = Field(None, description="风格提示（如：简洁/温暖/科技感）")


class GenerateCardResponse(BaseResponse):
    """记忆卡片生成响应模型"""
    conversation_id: str
    # 卡片图片在 MinIO 中的路径
    card_object_key: str = Field(..., description="生成的卡片图片在 MinIO 中的存储路径")
    card_url: Optional[str] = Field(None, description="预签名访问 URL（7天有效）")
    # 使用的 SD 提示词（用于调试）
    sd_prompt: Optional[str] = Field(None, description="Stable Diffusion 提示词")
    processed_at: datetime = Field(default_factory=datetime.now)


# ──────────────────────────────────────────────
# Redis 任务消息模型
# ──────────────────────────────────────────────

class TranscribeTaskMessage(BaseModel):
    """Redis 队列中的转写任务消息格式（与 Java 端对应）"""
    task_id: str = Field(alias="taskId")
    conversation_id: str = Field(alias="conversationId")
    audio_object_key: str = Field(alias="audioObjectKey")
    bucket: str
    language: str = "auto"
    created_at: Optional[str] = Field(None, alias="createdAt")
    status: str = "pending"
    priority: int = 0

    model_config = {"populate_by_name": True}


# ──────────────────────────────────────────────
# 回调控制面数据模型
# ──────────────────────────────────────────────

class CallbackPayload(BaseModel):
    """回调控制面的数据格式（POST 到控制面 /api/callback/transcribe-result）"""
    task_id: str = Field(..., serialization_alias="taskId", description="任务 ID")
    conversation_id: str = Field(..., serialization_alias="conversationId", description="对话 ID")
    status: str = Field(..., description="处理状态：done/failed")
    # 转写结果
    transcript: Optional[str] = Field(None, description="完整转写文本")
    segments: Optional[List[Dict[str, Any]]] = Field(None, description="带时间戳的转写片段")
    speakers: Optional[List[Dict[str, Any]]] = Field(None, description="说话人信息")
    language: Optional[str] = Field(None, description="检测到的语言")
    duration_seconds: Optional[float] = Field(None, serialization_alias="durationSeconds", description="音频时长（秒）")
    # 摘要结果
    summary: Optional[str] = Field(None, description="对话摘要")
    key_persons: Optional[List[str]] = Field(None, serialization_alias="key_persons", description="关键人物列表")
    key_topics: Optional[List[str]] = Field(None, serialization_alias="key_topics", description="关键话题列表")
    sentiment: Optional[str] = Field(None, description="情感倾向")
    sentiment_score: Optional[float] = Field(None, serialization_alias="sentiment_score", description="情感分数")
    participants: Optional[str] = Field(None, description="逗号分隔的参与者")
    # 向量嵌入
    embedding: Optional[List[float]] = Field(None, description="语义向量嵌入")
    # 记忆卡片
    card_object_key: Optional[str] = Field(None, serialization_alias="card_object_key", description="记忆卡片 MinIO 路径")
    card_url: Optional[str] = Field(None, serialization_alias="card_url", description="记忆卡片预签名 URL")
    # 错误信息（失败时填充）
    error_message: Optional[str] = Field(None, serialization_alias="error_message", description="错误详情")
    # 处理时间戳
    processed_at: datetime = Field(default_factory=datetime.now, serialization_alias="processed_at")

    model_config = {"populate_by_name": True}
