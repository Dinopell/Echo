# 部署指南

> 本文档描述如何将 Project Echo 部署到生产环境。推荐在 Mac Mini M4 (16GB+) 或同等配置的本地服务器上运行，确保所有数据留在本地。

---

## 硬件要求

### 推荐配置

| 资源 | 最低要求 | 推荐配置 |
|------|---------|---------|
| CPU | 4 核 x86 | Apple M4 / 8 核 x86 |
| RAM | 8 GB | 16 GB+ |
| 磁盘 | 20 GB SSD | 100 GB+ SSD |
| GPU | 无 (CPU 推理) | Apple GPU / NVIDIA GPU (加速推理) |
| 网络 | 局域网 | 千兆局域网 (P2P 同步) |

### 推荐设备

- **Apple Mac Mini M4 16GB** — 最佳性价比，统一内存架构支持 GPU 加速
- **Mac Studio M2 Max** — 高性能场景
- **x86 Server + NVIDIA GPU** — Linux 部署场景

### 资源预估

| 组件 | 内存占用 | 磁盘占用 |
|------|---------|---------|
| Redis | ~512 MB | ~1 GB (数据卷) |
| Neo4j | ~1 GB (heap) | ~5 GB (数据卷) |
| MinIO | ~256 MB | 取决于音频量 |
| 数据面 (FastAPI) | ~2-4 GB (含模型) | ~3 GB (模型缓存) |
| 控制面 (Spring Boot) | ~512 MB | ~200 MB |
| Ollama (Qwen2.5:7b) | ~5 GB | ~4.7 GB (模型文件) |
| **合计** | **~9-11 GB** | **~15 GB+** |

---

## Docker Compose 一键部署

### 步骤 1：准备环境

```bash
# 克隆项目
git clone <repo-url> && cd Echo

# 复制并编辑环境变量
cp .env.example .env
```

### 步骤 2：配置生产环境变量

编辑 `.env` 文件，**必须修改以下配置**：

```bash
# ── 安全相关（必须修改）──────────────────────
# Neo4j 密码
NEO4J_PASSWORD=your_strong_neo4j_password

# MinIO 密钥
MINIO_ACCESS_KEY=your_minio_access_key
MINIO_SECRET_KEY=your_minio_secret_key

# JWT 签名密钥（生成方式: openssl rand -hex 32）
JWT_SECRET=your_256bit_jwt_secret

# 音频加密密钥（生成方式: openssl rand -base64 32）
AUDIO_ENCRYPT_KEY=your_base64_encoded_32byte_key

# Redis 密码
REDIS_PASSWORD=your_redis_password

# P2P 签名密钥（生成方式: openssl rand -hex 32）
P2P_DEVICE_SECRET=your_p2p_device_secret
```

### 步骤 3：启动服务

```bash
# 构建并启动所有服务
docker compose up -d --build

# 查看服务状态
docker compose ps

# 查看日志
docker compose logs -f
```

### 步骤 4：初始化 Neo4j

```bash
# 首次启动后执行初始化 Cypher
docker exec -it echo-neo4j cypher-shell \
  -u neo4j -p your_strong_neo4j_password \
  -f /docker-entrypoint-initdb.d/init.cypher
```

### 步骤 5：启动 Ollama

```bash
# 宿主机上启动 Ollama（AI 推理引擎，不在 Docker 内）
ollama serve

# 拉取模型
ollama pull qwen2.5:7b
```

> Ollama 运行在宿主机，数据面容器通过 `host.docker.internal:11434` 访问。

### 步骤 6：验证部署

`docker compose up -d --build` 已包含控制面容器（`echo-control-plane`），无需单独启动 Gradle。

若需在宿主机以开发模式运行控制面，可跳过 compose 中的 `control-plane` 服务：

```bash
docker compose up -d --build redis neo4j minio minio-init data-plane
cd control-plane && ./gradlew bootRun
```

### 步骤 7：健康检查

```bash
# 检查所有服务健康状态
echo "=== Redis ===" && \
docker exec echo-redis redis-cli ping && \
echo "=== Neo4j ===" && \
curl -s http://localhost:7474 && \
echo "=== MinIO ===" && \
curl -s http://localhost:9000/minio/health/live && \
echo "=== 数据面 ===" && \
curl -s http://localhost:8001/health && \
echo "=== 控制面 ===" && \
curl -s http://localhost:8080/actuator/health
```

---

## 环境变量生产配置

### 完整配置清单

| 变量 | 生产建议 | 说明 |
|------|---------|------|
| `NEO4J_PASSWORD` | 强密码 | 默认密码仅限开发 |
| `MINIO_ACCESS_KEY` | 自定义 | 默认值仅限开发 |
| `MINIO_SECRET_KEY` | 自定义 (32+ 字符) | 默认值仅限开发 |
| `MINIO_SECURE` | `true` | 生产环境建议启用 HTTPS |
| `REDIS_PASSWORD` | 强密码 | 生产环境必须设置 |
| `JWT_SECRET` | 256 位随机字符串 | 生产环境必须修改 |
| `JWT_ENABLED` | `true`（生产）/ `false`（开发） | 启用后 API 需 Bearer Token |
| `ECHO_API_KEY` | 随机密钥 | 换取 JWT 的 API Key |
| `JWT_EXPIRATION_MS` | `86400000` | Token 有效期 (默认 24h) |
| `AUDIO_ENCRYPT_KEY` | Base64 编码 32 字节 | 不设置则音频不加密 |
| `P2P_DEVICE_SECRET` | 随机密钥 | P2P 签名验证用 |
| `WHISPER_MODEL` | `medium` 或 `large-v3` | 更高精度 (需要更多内存) |
| `WHISPER_DEVICE` | `cuda` / `mps` | 有 GPU 时加速推理 |
| `LOG_LEVEL` | `WARNING` | 生产环境减少日志量 |

### WhisperX 模型选择

| 模型 | VRAM | 速度 (1h 音频) | 精度 | 推荐场景 |
|------|------|---------------|------|---------|
| `tiny` | ~1 GB | ~5 min | 低 | 快速预览 |
| `base` | ~1 GB | ~10 min | 中 | 日常使用 (默认) |
| `small` | ~2 GB | ~20 min | 中高 | 精度优先 |
| `medium` | ~5 GB | ~40 min | 高 | 生产环境推荐 |
| `large-v3` | ~10 GB | ~80 min | 最高 | 最高精度需求 |

---

## MinIO 和 Neo4j 初始化

### MinIO 初始化

Docker Compose 中的 `minio-init` 服务会自动执行 `infrastructure/minio/init.sh`，创建以下 Bucket：

| Bucket | 用途 | 策略 |
|--------|------|------|
| `raw-audio` | 加密音频存储 | 私有 |
| `transcriptions` | 转写结果 JSON | 私有 |
| `memory-cards` | 记忆卡片图片 | 私有 |
| `snapshots` | P2P 同步快照 | 私有 |

如需手动初始化：

```bash
# 进入 minio-init 容器
docker compose run --rm minio-init
```

### Neo4j 初始化

`infrastructure/neo4j/init.cypher` 创建以下数据库结构：

- **唯一约束**：`Person.name`、`Conversation.conversationId`
- **普通索引**：mentionCount、lastSeen、recordedAt、status、taskId
- **全文索引**：transcript + summary
- **向量索引**：embeddingVector (512 维, cosine)

手动执行：

```bash
docker exec -it echo-neo4j cypher-shell \
  -u neo4j -p <password> \
  -f /docker-entrypoint-initdb.d/init.cypher
```

验证初始化结果：

```bash
docker exec -it echo-neo4j cypher-shell \
  -u neo4j -p <password> \
  "SHOW INDEXES; SHOW CONSTRAINTS;"
```

---

## 健康检查与监控

### Docker 内置健康检查

Docker Compose 已为所有服务配置 `healthcheck`：

```bash
# 一键查看所有服务健康状态
docker compose ps
# STATUS 列显示 "healthy" 表示服务正常
```

### 控制面 Actuator

| 端点 | 说明 |
|------|------|
| `GET /actuator/health` | 健康检查（含 Redis、Neo4j 连接状态） |
| `GET /actuator/info` | 应用信息 |
| `GET /actuator/metrics` | JVM 指标 |

```bash
# 详细健康检查
curl http://localhost:8080/actuator/health | jq .
```

### 数据面健康检查

```bash
curl http://localhost:8001/health | jq .
```

### Redis 健康检查

```bash
docker exec echo-redis redis-cli ping
# PONG

# 查看内存使用
docker exec echo-redis redis-cli info memory | grep used_memory_human
```

### Neo4j 健康检查

```bash
# Bolt 协议连接测试
docker exec echo-neo4j cypher-shell -u neo4j -p <password> "RETURN 1"

# 查看数据库大小
docker exec echo-neo4j cypher-shell -u neo4j -p <password> \
  "CALL dbms.queryLog() YIELD query RETURN count(*) AS queryCount"
```

### MinIO 健康检查

```bash
# Liveness 探针
curl http://localhost:9000/minio/health/live

# Readiness 探针
curl http://localhost:9000/minio/health/ready

# 查看 Bucket 使用量
docker exec echo-minio mc du echominio
```

---

## 备份与恢复

### Redis 备份

```bash
# 触发 RDB 快照
docker exec echo-redis redis-cli BGSAVE

# 复制 RDB 文件到宿主机
docker cp echo-redis:/data/dump.rdb ./backup/redis-$(date +%Y%m%d).rdb
```

恢复：将 RDB 文件复制回 Redis 数据目录后重启容器。

### Neo4j 备份

```bash
# 在线备份 (Neo4j Community 使用离线导出)
docker exec echo-neo4j neo4j-admin database dump neo4j \
  --to-path=/backup/

# 或导出 Cypher 脚本
docker exec echo-neo4j cypher-shell -u neo4j -p <password> \
  "CALL apoc.export.cypher.all('/backup/full-dump.cypher', {})"
```

恢复：

```bash
# 从备份恢复
docker exec echo-neo4j neo4j-admin database load neo4j \
  --from-path=/backup/
```

### MinIO 备份

```bash
# 使用 mc mirror 备份所有 Bucket
docker run --rm --network echo-network \
  -v /path/to/backup:/backup \
  minio/mc alias set echominio http://minio:9000 \
    <access_key> <secret_key> && \
  mc mirror echominio /backup/minio-$(date +%Y%m%d)
```

### 全量备份脚本

```bash
#!/bin/bash
# backup.sh — Echo 全量备份脚本
BACKUP_DIR="./backups/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

echo "=== 备份 Redis ==="
docker exec echo-redis redis-cli BGSAVE
sleep 2
docker cp echo-redis:/data/dump.rdb "$BACKUP_DIR/redis.rdb"

echo "=== 备份 Neo4j ==="
docker exec echo-neo4j neo4j-admin database dump neo4j \
  --to-path="$BACKUP_DIR/"

echo "=== 备份完成: $BACKUP_DIR ==="
ls -la "$BACKUP_DIR"
```

---

## 故障排查常见问题

### 服务无法启动

#### Redis 连接失败

```
Error: Redis 连接失败
```

**排查步骤**：

1. 检查 Redis 容器状态：`docker compose ps redis`
2. 检查端口占用：`lsof -i :6379`
3. 测试连接：`docker exec echo-redis redis-cli ping`
4. 检查密码配置是否一致

#### Neo4j 连接失败

```
Error: Neo4j 连接超时
```

**排查步骤**：

1. 检查 Neo4j 是否完成初始化（首次启动需要 30 秒+）
2. 验证密码：`docker exec echo-neo4j cypher-shell -u neo4j -p <password> "RETURN 1"`
3. 检查 Bolt 端口：`lsof -i :7687`
4. 查看 Neo4j 日志：`docker compose logs neo4j`

#### MinIO 连接失败

**排查步骤**：

1. 检查健康状态：`curl http://localhost:9000/minio/health/live`
2. 检查凭证是否匹配 `.env` 配置
3. 查看 MinIO 日志：`docker compose logs minio`

### 数据面问题

#### WhisperX 转写失败

```
Error: 转写处理失败
```

**排查步骤**：

1. 确认 ffmpeg 已安装：`docker exec echo-data-plane ffmpeg -version`
2. 检查音频文件是否存在：通过 MinIO Console 查看 `raw-audio` Bucket
3. 检查内存是否充足：WhisperX base 模型需要约 2 GB RAM
4. 查看数据面日志：`docker compose logs data-plane`

#### Ollama 调用超时

```
Error: 摘要生成超时
```

**排查步骤**：

1. 确认 Ollama 服务运行：`curl http://localhost:11434/api/tags`
2. 确认模型已拉取：`ollama list`
3. Docker 容器内需要使用 `host.docker.internal` 访问宿主机 Ollama
4. 调整超时时间：`OLLAMA_TIMEOUT=300`（默认 120 秒）

#### 模型下载失败

**排查步骤**：

1. 检查网络连接（HuggingFace 可能需要代理）
2. 手动下载模型到本地缓存：
   ```bash
   export HF_HOME=~/.cache/huggingface
   python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-small-zh-v1.5')"
   ```
3. 检查磁盘空间是否充足

### 控制面问题

#### 音频上传失败

```
Error: 音频文件上传至存储失败 (1003)
```

**排查步骤**：

1. 检查 MinIO 运行状态
2. 确认 Bucket 已创建：通过 MinIO Console (http://localhost:9001) 查看
3. 检查文件大小是否超过 500 MB 限制
4. 查看控制面日志：`./gradlew bootRun` 的控制台输出

#### 任务状态一直为 pending

**排查步骤**：

1. 检查数据面消费者是否运行：`curl http://localhost:8001/health`，`consumer_running` 应为 `true`
2. 检查 Redis 队列是否有积压：
   ```bash
   docker exec echo-redis redis-cli LLEN echo:queue:transcribe
   ```
3. 查看数据面日志是否有消费错误

#### 图谱查询返回空

**排查步骤**：

1. 确认 Neo4j 初始化脚本已执行
2. 确认有成功处理的对话（status=done）
3. 在 Neo4j Browser 中手动查询：
   ```cypher
   MATCH (c:Conversation) RETURN c LIMIT 10
   ```

### P2P 同步问题

#### P2P 同步失败

**排查步骤**：

1. 确认 `P2P_ENABLED=true`
2. 确认两端设备在同一局域网
3. 测试网络连通性：`curl http://<peer-ip>:8080/actuator/health`
4. 检查签名密钥是否匹配

### 日志查看

```bash
# 查看所有服务日志
docker compose logs -f

# 查看特定服务日志
docker compose logs -f data-plane
docker compose logs -f neo4j

# 控制面日志（本地运行）
cd control-plane && ./gradlew bootRun
# 日志直接输出到控制台

# 查看最近 100 行
docker compose logs --tail 100 data-plane
```

### 性能优化建议

| 场景 | 优化方案 |
|------|---------|
| 转写速度慢 | 使用 GPU (`WHISPER_DEVICE=cuda/mps`)，升级模型 |
| 摘要生成慢 | 使用更大参数的 Ollama 模型 |
| 内存不足 | 降低 `WHISPER_BATCH_SIZE`，使用更小的 WhisperX 模型 |
| Redis 内存高 | 调整 `maxmemory-policy`，增加 `maxmemory` |
| Neo4j 查询慢 | 检查索引是否创建，执行 `SHOW INDEXES` 验证 |
