"""
Project Echo — 记忆卡片图像生成服务

功能：
- 根据情感分析结果构建 SD prompt
- 调用本地 ComfyUI API 生成图像（可选）
- 降级方案：生成精美 SVG 格式记忆卡片
- 支持三种风格：artistic（艺术诠释）、ambient（氛围场景）、abstract（抽象联结）
- 生成后自动上传 MinIO memory-cards bucket
"""

import asyncio
import io
import json
import logging
import random
import time
import uuid
from datetime import datetime
from typing import Optional

import httpx

from config import get_settings
from models.schemas import GenerateCardRequest, GenerateCardResponse

logger = logging.getLogger(__name__)

# ── 风格提示词库 ──────────────────────────────────────────────────────────────

STYLE_PROMPTS = {
    "artistic": {
        "positive": "warm watercolor painting, golden hour light, soft brushstrokes, cozy atmosphere, impressionist style",
        "neutral":  "ink wash painting, minimalist, gentle grey tones, zen aesthetic, traditional art",
        "negative": "oil painting, dramatic lighting, deep purple hues, expressionist style, emotional depth",
    },
    "ambient": {
        "positive": "sunlit forest glade, soft bokeh, morning mist, serene landscape, golden particles",
        "neutral":  "quiet evening cityscape, soft window light, calm blue tones, peaceful ambiance",
        "negative": "rainy night window, reflective puddles, moody street light, introspective mood",
    },
    "abstract": {
        "positive": "flowing warm colors, golden spiral patterns, light particles, radiant energy, joy",
        "neutral":  "geometric balance, cool blue and grey gradients, structured patterns, harmony",
        "negative": "deep ocean abstract, swirling dark blues, melancholic patterns, introspective energy",
    },
}

QUALITY_SUFFIX = (
    "high quality, detailed, 8k resolution, professional photography, "
    "beautiful composition, artistic masterpiece"
)
NEGATIVE_PROMPT = (
    "ugly, blurry, low quality, watermark, text, signature, "
    "nsfw, violence, disturbing content"
)


class ImageService:
    """
    记忆卡片生成服务

    主流程：
    1. 根据情感分数和风格选择合适的 SD prompt
    2. 尝试调用本地 ComfyUI API 生成图像
    3. 如果 ComfyUI 不可用，降级为 SVG 矢量卡片
    4. 将结果上传到 MinIO memory-cards bucket
    5. 生成预签名 URL（7天有效）
    """

    def __init__(self):
        self.settings = get_settings()

    async def generate_card(self, request: GenerateCardRequest) -> GenerateCardResponse:
        """
        生成记忆卡片（异步入口）

        Args:
            request: 卡片生成请求（含摘要、情感分数、风格）

        Returns:
            GenerateCardResponse: 生成结果（含 MinIO 路径和预签名 URL）
        """
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(None, self._generate_sync, request)
        return result

    def generate_memory_card(self, request: GenerateCardRequest) -> GenerateCardResponse:
        """generate_card 的同步别名，供消费者流水线直接调用"""
        return self._generate_sync(request)

    def _generate_sync(self, request: GenerateCardRequest) -> GenerateCardResponse:
        """同步卡片生成实现"""
        # 1. 构建 SD prompt
        sd_prompt = self._build_sd_prompt(request)
        logger.info(
            "构建 SD prompt: conversation_id=%s, style=%s",
            request.conversation_id, request.style
        )

        # 2. 尝试 ComfyUI 生成（如不可用则降级）
        card_data: Optional[bytes] = None
        content_type = "image/svg+xml"
        file_suffix = ".svg"

        try:
            card_data = self._call_comfyui(sd_prompt)
            if card_data:
                content_type = "image/png"
                file_suffix = ".png"
                logger.info("ComfyUI 图像生成成功")
        except Exception as e:
            logger.info("ComfyUI 不可用，使用 SVG 降级方案: %s", e)

        # 3. 降级：生成 SVG 卡片
        if not card_data:
            svg_content = self._render_card_svg(request, sd_prompt)
            card_data = svg_content.encode("utf-8")

        # 4. 上传到 MinIO
        object_key = f"cards/{request.conversation_id}/{uuid.uuid4()}{file_suffix}"
        from services.minio_service import MinioService
        minio = MinioService()
        minio.upload_bytes(
            self.settings.minio_bucket_memory_cards,
            object_key,
            card_data,
            content_type,
        )
        logger.info(
            "记忆卡片已生成并上传: conversation_id=%s, key=%s",
            request.conversation_id, object_key
        )

        # 5. 生成预签名 URL（7天有效）
        try:
            card_url = minio.get_presigned_url(
                self.settings.minio_bucket_memory_cards,
                object_key,
            )
        except Exception as e:
            logger.warning("生成预签名 URL 失败: %s", e)
            card_url = None

        return GenerateCardResponse(
            conversation_id=request.conversation_id,
            card_object_key=object_key,
            card_url=card_url,
            sd_prompt=sd_prompt,
        )

    def _build_sd_prompt(self, request: GenerateCardRequest) -> str:
        """
        根据情感分析结果构建 Stable Diffusion prompt

        风格映射：
        - sentiment_score > 0.3  → positive 风格
        - sentiment_score < -0.3 → negative 风格
        - 其他                   → neutral 风格
        """
        score = request.sentiment_score
        if score > 0.3:
            mood = "positive"
        elif score < -0.3:
            mood = "negative"
        else:
            mood = "neutral"

        # 优先使用用户指定风格，否则根据情感分数选择
        style = request.style if request.style in STYLE_PROMPTS else "ambient"
        base_prompt = STYLE_PROMPTS[style][mood]

        # 加入摘要关键词（取前 50 字）
        summary_hint = request.summary[:50].replace("\n", " ")

        # 加入用户风格提示
        style_hint = f", {request.style_hint}" if request.style_hint else ""

        # 加入情感峰值片段
        peak_hint = ""
        if request.peak_segment:
            peak_hint = f", inspired by: {request.peak_segment[:30]}"

        prompt = f"{base_prompt}{style_hint}{peak_hint}, {QUALITY_SUFFIX}"
        logger.debug("SD prompt 构建完成: 情感=%s, 风格=%s", mood, style)
        return prompt

    def _call_comfyui(self, prompt: str) -> Optional[bytes]:
        """
        调用本地 ComfyUI API 生成图像

        Args:
            prompt: Stable Diffusion 提示词

        Returns:
            bytes: 图像二进制数据，失败返回 None
        """
        comfyui_url = "http://localhost:8188"

        # ComfyUI workflow payload（文字转图像基础 workflow）
        workflow = {
            "3": {
                "inputs": {
                    "seed": random.randint(0, 2**32),
                    "steps": 20,
                    "cfg": 7,
                    "sampler_name": "euler",
                    "scheduler": "normal",
                    "denoise": 1,
                    "model": ["4", 0],
                    "positive": ["6", 0],
                    "negative": ["7", 0],
                    "latent_image": ["5", 0],
                },
                "class_type": "KSampler",
            },
            "4": {
                "inputs": {"ckpt_name": "v1-5-pruned-emaonly.ckpt"},
                "class_type": "CheckpointLoaderSimple",
            },
            "5": {
                "inputs": {"width": 512, "height": 512, "batch_size": 1},
                "class_type": "EmptyLatentImage",
            },
            "6": {
                "inputs": {"text": prompt, "clip": ["4", 1]},
                "class_type": "CLIPTextEncode",
            },
            "7": {
                "inputs": {"text": NEGATIVE_PROMPT, "clip": ["4", 1]},
                "class_type": "CLIPTextEncode",
            },
            "8": {
                "inputs": {"samples": ["3", 0], "vae": ["4", 2]},
                "class_type": "VAEDecode",
            },
            "9": {
                "inputs": {
                    "filename_prefix": "echo_memory",
                    "images": ["8", 0],
                },
                "class_type": "SaveImage",
            },
        }

        with httpx.Client(timeout=120.0) as client:
            # 提交任务
            resp = client.post(
                f"{comfyui_url}/prompt",
                json={"prompt": workflow},
            )
            resp.raise_for_status()
            prompt_id = resp.json()["prompt_id"]

            # 轮询等待完成（最长 90 秒）
            for _ in range(90):
                time.sleep(1)
                history_resp = client.get(f"{comfyui_url}/history/{prompt_id}")
                history = history_resp.json()
                if prompt_id in history:
                    outputs = history[prompt_id].get("outputs", {})
                    for node_output in outputs.values():
                        for img_info in node_output.get("images", []):
                            img_resp = client.get(
                                f"{comfyui_url}/view",
                                params={
                                    "filename": img_info["filename"],
                                    "subfolder": img_info.get("subfolder", ""),
                                    "type": img_info.get("type", "output"),
                                },
                            )
                            img_resp.raise_for_status()
                            return img_resp.content

        logger.warning("ComfyUI 任务超时")
        return None

    def _render_card_svg(self, request: GenerateCardRequest, sd_prompt: str = "") -> str:
        """
        生成精美 SVG 格式的记忆卡片（降级方案）

        根据情感分数选择配色主题。
        """
        now = datetime.now().strftime("%Y-%m-%d")
        score = request.sentiment_score

        # 根据情感分数选择配色
        if score > 0.3:
            # 正面情感：温暖金色调
            bg_color = "#1a1a0e"
            accent_color = "#f4a261"
            text_color = "#fef3c7"
        elif score < -0.3:
            # 负面情感：深沉蓝紫调
            bg_color = "#0f0a1e"
            accent_color = "#7c3aed"
            text_color = "#e0d9f7"
        else:
            # 中性：经典深色调
            bg_color = "#1a1a2e"
            accent_color = "#e94560"
            text_color = "#e0e0e0"

        summary_lines = self._wrap_text(request.summary, max_chars=32)
        # 生成内容文本 SVG 元素
        text_elements = []
        for i, line in enumerate(summary_lines[:5]):
            # 对特殊字符进行 XML 转义
            safe_line = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            text_elements.append(
                f'    <tspan x="40" dy="1.5em">{safe_line}</tspan>'
            )
        summary_svg = "\n".join(text_elements)

        # 情感指示器
        sentiment_label = (
            "▲ 积极" if score > 0.3
            else "▼ 消极" if score < -0.3
            else "● 平静"
        )

        safe_conv_id = request.conversation_id[:16]

        return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg width="400" height="300" xmlns="http://www.w3.org/2000/svg">
  <!-- 卡片背景 -->
  <defs>
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:{bg_color};stop-opacity:1" />
      <stop offset="100%" style="stop-color:{bg_color}cc;stop-opacity:1" />
    </linearGradient>
  </defs>
  <rect width="400" height="300" rx="18" ry="18" fill="url(#bgGrad)"/>
  <rect x="3" y="3" width="394" height="294" rx="16" ry="16" fill="none"
        stroke="{accent_color}" stroke-width="1.5" stroke-opacity="0.7"/>

  <!-- 顶部装饰 -->
  <rect x="0" y="0" width="400" height="4" rx="2" fill="{accent_color}" opacity="0.8"/>

  <!-- Echo 标识 -->
  <text x="40" y="38" font-family="monospace" font-size="13" fill="{accent_color}"
        font-weight="bold">◈ ECHO MEMORY</text>
  <text x="360" y="38" font-family="monospace" font-size="11" fill="#888888"
        text-anchor="end">{now}</text>

  <!-- 分隔线 -->
  <line x1="40" y1="52" x2="360" y2="52" stroke="{accent_color}"
        stroke-width="1" stroke-opacity="0.4"/>

  <!-- 摘要内容 -->
  <text font-family="'PingFang SC', 'Noto Sans CJK SC', sans-serif"
        font-size="14" fill="{text_color}" line-height="1.5">
    <tspan x="40" y="76">{summary_svg}</tspan>
  </text>

  <!-- 底部信息栏 -->
  <line x1="40" y1="265" x2="360" y2="265" stroke="{accent_color}"
        stroke-width="0.5" stroke-opacity="0.3"/>
  <text x="40" y="285" font-family="monospace" font-size="10"
        fill="#555555">{safe_conv_id}...</text>
  <text x="360" y="285" font-family="monospace" font-size="10"
        fill="{accent_color}" text-anchor="end">{sentiment_label}</text>
</svg>"""

    @staticmethod
    def _wrap_text(text: str, max_chars: int = 32) -> list[str]:
        """简单按字符数换行（适配中文宽字符）"""
        lines = []
        current = ""
        for char in text:
            current += char
            if len(current) >= max_chars:
                lines.append(current)
                current = ""
        if current:
            lines.append(current)
        return lines[:5]
