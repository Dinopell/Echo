# ============================================================
# Project Echo — FastAPI 数据面 Dockerfile
# 基于 python:3.11-slim，包含 ffmpeg（WhisperX 依赖）
#
# 国内网络拉取基础镜像失败时：
#   DOCKER_REGISTRY_PREFIX=docker.m.daocloud.io/ docker compose build data-plane
# ============================================================

ARG REGISTRY_PREFIX=
ARG PYTHON_IMAGE=python:3.11-slim

FROM ${REGISTRY_PREFIX}${PYTHON_IMAGE}

# 设置工作目录
WORKDIR /app

# 替换 Debian 源为阿里云镜像（国内网络加速）
RUN sed -i 's|http://deb.debian.org|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources

# 设置 Python 环境变量——禁止写 .pyc、禁止缓冲输出
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# 安装系统依赖（ffmpeg + PyAV 编译头文件，whisperx 需要）
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libavformat-dev \
    libavcodec-dev \
    libavdevice-dev \
    libavutil-dev \
    libswscale-dev \
    libswresample-dev \
    pkg-config \
    libsndfile1 \
    curl \
    git \
    gcc \
    g++ \
    && rm -rf /var/lib/apt/lists/*

# 先复制依赖文件，利用 Docker 层缓存加速构建
COPY requirements.txt .

# 安装 Python 依赖（av 使用预编译 wheel，版本约束见 requirements.txt）
RUN pip install --no-cache-dir --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple && \
    pip install --no-cache-dir -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

# 复制应用代码
COPY . .

# 创建非 root 用户（安全最佳实践）
RUN useradd -m -u 1000 echouser && \
    chown -R echouser:echouser /app && \
    # 创建模型缓存目录，确保 echouser 可写
    mkdir -p /home/echouser/.cache/huggingface && \
    chown -R echouser:echouser /home/echouser/.cache

USER echouser

# 暴露服务端口
EXPOSE 8001

# 健康检查（start-period 设置较长，等待模型加载）
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -f http://localhost:8001/health || exit 1

# 启动命令（生产模式：单进程，由 Docker 管理）
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1", "--log-level", "info"]
