#!/bin/sh
# ============================================================
# Project Echo — MinIO Bucket 初始化脚本
# 
# 由 docker-compose 的 minio-init 服务在 MinIO 就绪后执行。
# 创建 Echo 项目所需的四个核心 bucket。
# ============================================================

set -e

MINIO_URL="http://minio:9000"
ACCESS_KEY="${MINIO_ACCESS_KEY:-echo_minio_admin}"
SECRET_KEY="${MINIO_SECRET_KEY:-echo_minio_secret}"

echo "============================================"
echo "  Echo MinIO Bucket 初始化"
echo "  MinIO URL: ${MINIO_URL}"
echo "============================================"

# 配置 MinIO Client（mc）连接
mc alias set echominio "${MINIO_URL}" "${ACCESS_KEY}" "${SECRET_KEY}"

echo ""
echo ">> 创建存储 Bucket..."

# ── 1. raw-audio：原始音频文件
if mc ls echominio/raw-audio > /dev/null 2>&1; then
    echo "   [SKIP] raw-audio 已存在"
else
    mc mb echominio/raw-audio
    echo "   [OK]   raw-audio 已创建"
fi

# ── 2. transcriptions：转写结果文件（JSON）
if mc ls echominio/transcriptions > /dev/null 2>&1; then
    echo "   [SKIP] transcriptions 已存在"
else
    mc mb echominio/transcriptions
    echo "   [OK]   transcriptions 已创建"
fi

# ── 3. memory-cards：AI 生成的记忆卡片图片
if mc ls echominio/memory-cards > /dev/null 2>&1; then
    echo "   [SKIP] memory-cards 已存在"
else
    mc mb echominio/memory-cards
    echo "   [OK]   memory-cards 已创建"
fi

# ── 4. snapshots：设备状态快照（P2P 同步用）
if mc ls echominio/snapshots > /dev/null 2>&1; then
    echo "   [SKIP] snapshots 已存在"
else
    mc mb echominio/snapshots
    echo "   [OK]   snapshots 已创建"
fi

echo ""
echo ">> 设置 Bucket 策略（私有访问，仅内部服务可读写）..."
# 所有 bucket 默认为私有策略，符合隐私优先原则
mc anonymous set none echominio/raw-audio
mc anonymous set none echominio/transcriptions
mc anonymous set none echominio/memory-cards
mc anonymous set none echominio/snapshots

echo ""
echo ">> 当前 Bucket 列表："
mc ls echominio

echo ""
echo "============================================"
echo "  MinIO 初始化完成！"
echo "============================================"
