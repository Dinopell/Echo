"""
Project Echo — Ollama LLM 摘要服务

使用本地 Ollama（Qwen2.5）模型对对话转写文本进行摘要提取。
所有 LLM 调用均在本地执行，不访问任何外部 API。

功能：
- 对话摘要生成
- 情感分析（-1.0 到 1.0 分数）
- 关键话题提取
- 口头填充语过滤
- 晨间胶囊（每日记忆精华）生成
"""

import asyncio
import json
import logging
import re
from typing import Optional, List

from config import get_settings
from models.schemas import (
    SummarizeRequest,
    SummarizeResponse,
    MorningCapsuleRequest,
    MorningCapsuleResponse,
)

logger = logging.getLogger(__name__)

# ── 提示词模板（中文优化版，使用结构化 JSON 输出 schema）──────────────────────

SUMMARIZE_PROMPT_TEMPLATE = """你是一个帮助用户整理记忆的 AI 助手。
请对以下对话转写文本进行分析，输出 JSON 格式结果。

对话内容：
{text}

{context_hint}

请严格输出以下 JSON 格式（不要输出任何其他内容，不要加代码块标记）：
{{
  "summary": "对话的核心摘要（2-4句话，简洁客观）",
  "key_persons": ["提及的关键人物列表（仅人名，不含称谓）"],
  "key_topics": ["关键话题列表（3-5个）"],
  "sentiment": "overall_sentiment",
  "sentiment_score": 0.0
}}

其中 sentiment 只能是 positive、negative 或 neutral，
sentiment_score 范围 -1.0（极度负面）到 1.0（极度正面），0.0 为中性。"""

SENTIMENT_PROMPT_TEMPLATE = """分析以下文本的情感倾向，仅输出 JSON 格式：

文本：{text}

输出格式（仅 JSON，无其他内容）：
{{
  "sentiment": "positive/negative/neutral",
  "score": 0.0,
  "confidence": 0.0,
  "reason": "简短说明"
}}

score 范围 -1.0 到 1.0，confidence 范围 0.0 到 1.0。"""

TOPICS_PROMPT_TEMPLATE = """从以下对话文本中提取关键话题，仅输出 JSON 格式：

对话文本：{text}

输出格式（仅 JSON，无其他内容）：
{{
  "topics": ["话题1", "话题2", "话题3"]
}}

提取 3-7 个最重要的话题，每个话题用 2-6 个字描述。"""

MORNING_CAPSULE_PROMPT_TEMPLATE = """你是一个帮助用户回顾昨日记忆的 AI 助手。
请根据以下多段对话摘要，生成一份简洁的"晨间胶囊"——帮助用户在新的一天开始时回顾昨天的重要记忆。

日期：{date}
对话摘要列表：
{summaries_text}

请严格输出以下 JSON 格式（不要输出任何其他内容）：
{{
  "capsule": "昨日记忆精华总结（3-5句话，温暖自然的语气）",
  "highlights": ["亮点1", "亮点2", "亮点3"],
  "people_mentioned": ["人名1", "人名2"],
  "mood_summary": "positive/negative/neutral"
}}"""

FILTER_FILLERS_PROMPT_TEMPLATE = """请过滤以下文本中的口头填充语（如：嗯、啊、那个、就是、对对对等），
保留语义完整性，仅输出过滤后的文本，不要有任何额外说明：

原文：{text}"""


class LLMService:
    """
    本地 LLM 服务（Ollama + Qwen2.5）

    负责：
    - 对话文本摘要生成
    - 情感分析（量化分数）
    - 关键话题提取
    - 口头填充语过滤
    - 晨间胶囊（每日记忆精华）生成
    所有 LLM 调用使用结构化 JSON 输出 schema。
    """

    def __init__(self):
        self.settings = get_settings()

    # ─────────────────────────────────────────────────────────────
    # 公开方法
    # ─────────────────────────────────────────────────────────────

    async def summarize(self, request: SummarizeRequest) -> SummarizeResponse:
        """
        生成对话摘要（含情感分析和关键话题）

        Args:
            request: 摘要请求

        Returns:
            SummarizeResponse: 结构化摘要结果
        """
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(None, self._summarize_sync, request)
        return result

    async def analyze_sentiment(self, text: str) -> dict:
        """
        分析情感倾向

        Args:
            text: 待分析文本

        Returns:
            dict: {"sentiment": str, "score": float, "confidence": float}
                  score 范围 -1.0（负面）到 1.0（正面）
        """
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(None, self._analyze_sentiment_sync, text)
        return result

    async def extract_topics(self, text: str) -> List[str]:
        """
        提取关键话题列表

        Args:
            text: 待分析文本

        Returns:
            List[str]: 关键话题列表
        """
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(None, self._extract_topics_sync, text)
        return result

    async def generate_morning_capsule(
        self, request: MorningCapsuleRequest
    ) -> MorningCapsuleResponse:
        """
        生成晨间胶囊（综合多段对话生成每日精华）

        Args:
            request: 晨间胶囊请求（含多段摘要）

        Returns:
            MorningCapsuleResponse: 每日记忆精华
        """
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None, self._generate_morning_capsule_sync, request
        )
        return result

    async def filter_fillers(self, text: str) -> str:
        """
        过滤口头填充语（嗯、啊、那个等）

        Args:
            text: 原始转写文本

        Returns:
            str: 过滤后的文本
        """
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(None, self._filter_fillers_sync, text)
        return result

    # ─────────────────────────────────────────────────────────────
    # 同步实现（在线程池中运行）
    # ─────────────────────────────────────────────────────────────

    def _summarize_sync(self, request: SummarizeRequest) -> SummarizeResponse:
        """同步摘要实现（在线程池中运行）"""
        context_hint = ""
        if request.context:
            context_hint = f"补充上下文：{request.context}"

        prompt = SUMMARIZE_PROMPT_TEMPLATE.format(
            text=request.text,
            context_hint=context_hint,
        )

        try:
            raw_text = self._call_ollama(prompt)
            parsed = self._parse_json_output(raw_text)

            return SummarizeResponse(
                conversation_id=request.conversation_id,
                summary=parsed.get("summary", raw_text[:500]),
                key_persons=parsed.get("key_persons", []),
                key_topics=parsed.get("key_topics", []),
                sentiment=parsed.get("sentiment", "neutral"),
                sentiment_score=float(parsed.get("sentiment_score", 0.0)),
            )

        except ImportError:
            logger.warning("ollama 未安装，摘要功能不可用")
            return SummarizeResponse(
                conversation_id=request.conversation_id,
                summary=f"[摘要功能未就绪] 原文节选：{request.text[:200]}...",
                key_persons=[],
                key_topics=[],
                sentiment="neutral",
                sentiment_score=0.0,
            )
        except Exception as e:
            logger.error("LLM 摘要调用失败: %s", e)
            raise

    def _analyze_sentiment_sync(self, text: str) -> dict:
        """同步情感分析实现"""
        prompt = SENTIMENT_PROMPT_TEMPLATE.format(text=text[:2000])
        try:
            raw_text = self._call_ollama(prompt, temperature=0.1)
            parsed = self._parse_json_output(raw_text)
            return {
                "sentiment": parsed.get("sentiment", "neutral"),
                "score": float(parsed.get("score", 0.0)),
                "confidence": float(parsed.get("confidence", 0.0)),
            }
        except ImportError:
            return {"sentiment": "neutral", "score": 0.0, "confidence": 0.0}
        except Exception as e:
            logger.warning("情感分析失败: %s", e)
            return {"sentiment": "neutral", "score": 0.0, "confidence": 0.0}

    def _extract_topics_sync(self, text: str) -> List[str]:
        """同步话题提取实现"""
        prompt = TOPICS_PROMPT_TEMPLATE.format(text=text[:3000])
        try:
            raw_text = self._call_ollama(prompt, temperature=0.2)
            parsed = self._parse_json_output(raw_text)
            return parsed.get("topics", [])
        except ImportError:
            return []
        except Exception as e:
            logger.warning("话题提取失败: %s", e)
            return []

    def _generate_morning_capsule_sync(
        self, request: MorningCapsuleRequest
    ) -> MorningCapsuleResponse:
        """同步晨间胶囊生成实现"""
        summaries_text = "\n".join(
            f"{i+1}. {s}" for i, s in enumerate(request.summaries)
        )
        prompt = MORNING_CAPSULE_PROMPT_TEMPLATE.format(
            date=request.date,
            summaries_text=summaries_text,
        )
        try:
            raw_text = self._call_ollama(prompt, temperature=0.4)
            parsed = self._parse_json_output(raw_text)
            return MorningCapsuleResponse(
                date=request.date,
                capsule=parsed.get("capsule", "昨日无特别记忆。"),
                highlights=parsed.get("highlights", []),
                people_mentioned=parsed.get("people_mentioned", []),
                mood_summary=parsed.get("mood_summary", "neutral"),
            )
        except ImportError:
            return MorningCapsuleResponse(
                date=request.date,
                capsule="[晨间胶囊功能未就绪，请安装 ollama]",
                highlights=[],
                people_mentioned=[],
                mood_summary="neutral",
            )
        except Exception as e:
            logger.error("晨间胶囊生成失败: %s", e)
            raise

    def _filter_fillers_sync(self, text: str) -> str:
        """同步口头填充语过滤实现"""
        prompt = FILTER_FILLERS_PROMPT_TEMPLATE.format(text=text)
        try:
            filtered = self._call_ollama(prompt, temperature=0.1)
            return filtered.strip()
        except ImportError:
            # 降级：使用正则表达式简单过滤
            return self._simple_filter_fillers(text)
        except Exception as e:
            logger.warning("LLM 填充语过滤失败，使用规则过滤: %s", e)
            return self._simple_filter_fillers(text)

    # ─────────────────────────────────────────────────────────────
    # 工具方法
    # ─────────────────────────────────────────────────────────────

    def _call_ollama(self, prompt: str, temperature: float = 0.3) -> str:
        """
        调用 Ollama API 执行推理

        Args:
            prompt: 提示词
            temperature: 生成温度（0.0-1.0）

        Returns:
            str: LLM 原始输出文本
        """
        import ollama

        client = ollama.Client(host=self.settings.ollama_base_url)
        response = client.chat(
            model=self.settings.ollama_model,
            messages=[{"role": "user", "content": prompt}],
            options={
                "temperature": temperature,
                "num_predict": 2048,
            },
        )
        raw_text = response["message"]["content"]
        # 隐私优先：只记录输出长度，不记录内容明文
        logger.debug("LLM 输出长度: %d 字符", len(raw_text))
        return raw_text

    @staticmethod
    def _parse_json_output(text: str) -> dict:
        """
        从 LLM 输出中提取 JSON 内容

        处理 LLM 可能输出额外文字或代码块标记的情况。
        """
        # 尝试直接解析
        try:
            return json.loads(text.strip())
        except json.JSONDecodeError:
            pass

        # 尝试从 markdown 代码块中提取
        code_block_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        if code_block_match:
            try:
                return json.loads(code_block_match.group(1))
            except json.JSONDecodeError:
                pass

        # 尝试找到 JSON 对象
        json_match = re.search(r"\{.*\}", text, re.DOTALL)
        if json_match:
            try:
                return json.loads(json_match.group())
            except json.JSONDecodeError:
                pass

        # 降级：返回空 dict，由调用方处理
        logger.warning("无法解析 LLM JSON 输出，使用原始文本")
        return {}

    @staticmethod
    def _simple_filter_fillers(text: str) -> str:
        """规则基础的简单填充语过滤（降级方案）"""
        fillers = ["嗯", "啊", "那个", "就是", "对对对", "然后呢", "呃", "哦", "嗯嗯"]
        result = text
        for filler in fillers:
            result = result.replace(filler, "")
        # 清理多余空格
        result = re.sub(r"\s+", " ", result).strip()
        return result
