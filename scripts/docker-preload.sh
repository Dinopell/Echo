#!/usr/bin/env bash
# 预拉取 Docker 基础镜像，避免 BuildKit 元数据请求 401 / 构建阶段拉取失败
# 用法: ./scripts/docker-preload.sh
#
# 说明:
#   - 基础设施镜像（redis/neo4j/minio）可使用 DOCKER_REGISTRY_PREFIX 加速
#   - 构建用基础镜像（temurin/python）始终直连 Docker Hub（daocloud 对 temurin 常 401）

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

infra_images=(
  "${PREFIX}redis:7-alpine"
  "${PREFIX}neo4j:5-community"
  "${PREFIX}minio/minio:latest"
  "${PREFIX}minio/mc:latest"
)

build_images=(
  "eclipse-temurin:17-jdk"
  "eclipse-temurin:17-jre"
  "python:3.11-slim"
)

echo "==> 基础设施镜像前缀: ${PREFIX:-<无，直连 Docker Hub>}"
for img in "${infra_images[@]}"; do
  echo "==> Pull $img"
  docker pull "$img"
done

echo "==> 构建基础镜像: 直连 Docker Hub（忽略 DOCKER_REGISTRY_PREFIX）"
for img in "${build_images[@]}"; do
  echo "==> Pull $img"
  docker pull "$img"
done

echo "==> 预拉取完成，可执行: docker compose build control-plane && docker compose up -d"
