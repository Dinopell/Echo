"""
Project Echo — 音频工具模块

提供音频格式转换和 VAD 预处理工具函数：
- 音频格式转换（各种格式 → WAV/MP3）
- 静音检测与裁剪
- 音频时长获取
- VAD 预处理（基于 ffmpeg）

依赖 ffmpeg（需要系统安装）。
"""

import asyncio
import logging
import os
import subprocess
import tempfile
from typing import Optional

logger = logging.getLogger(__name__)

# 支持的音频输入格式
SUPPORTED_FORMATS = {
    ".mp3", ".mp4", ".m4a", ".wav", ".flac",
    ".ogg", ".opus", ".aac", ".webm", ".wma",
}

# 目标采样率（WhisperX 要求 16kHz）
TARGET_SAMPLE_RATE = 16000


def convert_to_wav(input_path: str, output_path: Optional[str] = None) -> str:
    """
    将音频文件转换为 WAV 格式（单声道，16kHz）

    WhisperX 处理性能最优的格式为 16kHz 单声道 WAV。

    Args:
        input_path: 输入音频文件路径
        output_path: 输出 WAV 文件路径（不指定则自动生成临时文件）

    Returns:
        str: 输出 WAV 文件路径

    Raises:
        RuntimeError: ffmpeg 执行失败
        FileNotFoundError: 输入文件不存在
    """
    if not os.path.exists(input_path):
        raise FileNotFoundError(f"音频文件不存在: {input_path}")

    if output_path is None:
        tmp_fd, output_path = tempfile.mkstemp(suffix=".wav")
        os.close(tmp_fd)

    # 检查是否已经是 WAV 格式
    _, ext = os.path.splitext(input_path.lower())
    if ext == ".wav":
        # 仍然重采样确保格式正确
        pass

    cmd = [
        "ffmpeg",
        "-y",                # 覆盖输出文件
        "-i", input_path,    # 输入文件
        "-ar", str(TARGET_SAMPLE_RATE),  # 采样率 16kHz
        "-ac", "1",          # 单声道
        "-c:a", "pcm_s16le", # 16-bit PCM
        "-vn",               # 不包含视频流
        output_path,
    ]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=120,
        )
        if result.returncode != 0:
            logger.error("ffmpeg 转换失败: %s", result.stderr[-500:])
            raise RuntimeError(f"ffmpeg 音频转换失败: {result.stderr[-200:]}")

        logger.debug("音频格式转换完成: %s -> %s", input_path, output_path)
        return output_path

    except subprocess.TimeoutExpired:
        logger.error("ffmpeg 转换超时: %s", input_path)
        raise RuntimeError("音频格式转换超时（>120秒）")
    except FileNotFoundError:
        logger.error("ffmpeg 未安装，请安装 ffmpeg")
        raise RuntimeError("ffmpeg 未安装，请运行: apt-get install ffmpeg 或 brew install ffmpeg")


async def convert_to_wav_async(
    input_path: str, output_path: Optional[str] = None
) -> str:
    """
    异步版本的音频格式转换（避免阻塞事件循环）

    Args:
        input_path: 输入音频文件路径
        output_path: 输出 WAV 文件路径

    Returns:
        str: 输出 WAV 文件路径
    """
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, convert_to_wav, input_path, output_path)


def get_audio_duration(audio_path: str) -> Optional[float]:
    """
    获取音频文件时长（秒）

    使用 ffprobe 精确获取音频时长。

    Args:
        audio_path: 音频文件路径

    Returns:
        float: 时长（秒），获取失败返回 None
    """
    if not os.path.exists(audio_path):
        return None

    cmd = [
        "ffprobe",
        "-v", "quiet",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        audio_path,
    ]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode == 0 and result.stdout.strip():
            return float(result.stdout.strip())
    except (subprocess.TimeoutExpired, ValueError, FileNotFoundError) as e:
        logger.debug("获取音频时长失败: %s", e)

    return None


def is_supported_format(file_path: str) -> bool:
    """
    检查文件是否为支持的音频格式

    Args:
        file_path: 文件路径

    Returns:
        bool: True 表示支持
    """
    _, ext = os.path.splitext(file_path.lower())
    return ext in SUPPORTED_FORMATS


def trim_silence(
    input_path: str,
    output_path: Optional[str] = None,
    silence_threshold_db: float = -50.0,
    min_silence_duration: float = 0.5,
) -> str:
    """
    裁剪音频文件开头和结尾的静音部分

    Args:
        input_path: 输入音频文件路径
        output_path: 输出文件路径
        silence_threshold_db: 静音判断阈值（dB），默认 -50dB
        min_silence_duration: 最小静音时长（秒），默认 0.5s

    Returns:
        str: 输出文件路径
    """
    if output_path is None:
        ext = os.path.splitext(input_path)[1]
        tmp_fd, output_path = tempfile.mkstemp(suffix=ext)
        os.close(tmp_fd)

    # 使用 silenceremove 过滤器裁剪静音
    silence_filter = (
        f"silenceremove=start_periods=1:start_silence={min_silence_duration}"
        f":start_threshold={silence_threshold_db}dB"
        f":stop_periods=-1:stop_silence={min_silence_duration}"
        f":stop_threshold={silence_threshold_db}dB"
    )

    cmd = [
        "ffmpeg",
        "-y",
        "-i", input_path,
        "-af", silence_filter,
        output_path,
    ]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=120,
        )
        if result.returncode != 0:
            logger.warning("静音裁剪失败，返回原始文件: %s", result.stderr[-200:])
            return input_path
        return output_path
    except Exception as e:
        logger.warning("静音裁剪异常，返回原始文件: %s", e)
        return input_path


def split_audio_segments(
    input_path: str,
    segment_duration: float = 300.0,
    output_dir: Optional[str] = None,
) -> list[str]:
    """
    将长音频切分为多个短片段

    适用于超长音频（>30分钟）的分片处理，提升转写速度。

    Args:
        input_path: 输入音频文件路径
        segment_duration: 每段时长（秒），默认 300s（5分钟）
        output_dir: 输出目录（不指定则使用临时目录）

    Returns:
        list[str]: 切分后的文件路径列表
    """
    if output_dir is None:
        output_dir = tempfile.mkdtemp()

    ext = os.path.splitext(input_path)[1]
    output_pattern = os.path.join(output_dir, f"segment_%04d{ext}")

    cmd = [
        "ffmpeg",
        "-y",
        "-i", input_path,
        "-f", "segment",
        "-segment_time", str(segment_duration),
        "-c", "copy",
        "-reset_timestamps", "1",
        output_pattern,
    ]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=300,
        )
        if result.returncode != 0:
            logger.error("音频切分失败: %s", result.stderr[-200:])
            return [input_path]  # 降级：返回原始文件

        # 收集生成的片段文件（按序排序）
        segments = sorted([
            os.path.join(output_dir, f)
            for f in os.listdir(output_dir)
            if f.startswith("segment_") and f.endswith(ext)
        ])
        logger.info("音频切分完成: %d 个片段", len(segments))
        return segments

    except Exception as e:
        logger.warning("音频切分异常，返回原始文件: %s", e)
        return [input_path]
