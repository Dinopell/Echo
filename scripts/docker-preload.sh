#!/usr/bin/env bash
# 预拉取 Docker 基础镜像，避免 BuildKit 元数据请求 401 / 构建阶段拉取失败
# 用法: ./scripts/docker-preload.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

PREFIX="${DOCKER_REGISTRY_PREFIX:-}"

images=(
  "${PREFIX}redis:7-alpine"
  "${PREFIX}neo4j:5-community"
  "${PREFIX}minio/minio:latest"
  "${PREFIX}minio/mc:latest"
  "${PREFIX}eclipse-temurin:17-jdk"
  "${PREFIX}eclipse-temurin:17-jre"
  "${PREFIX}python:3.11-slim"
)

echo "==> 使用镜像前缀: ${PREFIX:-<无，直连 Docker Hub>}"
for img in "${images[@]}"; do
  echo "==> Pull $img"
  docker pull "$img"
done

echo "==> 预拉取完成，可执行: docker compose up -d --build"
