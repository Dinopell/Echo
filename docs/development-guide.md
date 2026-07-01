# 开发环境搭建指南

> 本文档帮助你从零搭建 Project Echo 的完整开发环境，包括控制面 (Spring Boot) 和数据面 (FastAPI) 的本地运行。

---

## 环境要求

| 依赖 | 最低版本 | 推荐版本 | 用途 |
|------|---------|---------|------|
| Java JDK | 17 | 21 (LTS) | 控制面编译运行 |
| Python | 3.11 | 3.12 | 数据面运行时 |
| Docker | 24.0+ | 最新 | 基础设施容器化 |
| Docker Compose | v2.20+ | 最新 | 容器编排 |
| Git | 2.40+ | 最新 | 版本管理 |
| Ollama | 最新 | 最新 | 本地 LLM 推理 |

### 硬件要求

| 资源 | 最低 | 推荐 |
|------|------|------|
| CPU | 4 核 | Apple M4 / 8 核 x86 |
| RAM | 8 GB | 16 GB+ |
| 磁盘 | 20 GB 可用 | 50 GB+ (含模型缓存) |

> **注意**：WhisperX 和 Qwen2.5 在 CPU 模式下推理较慢，推荐使用 Apple Silicon Mac 或配备 NVIDIA GPU 的设备。

---

## 依赖安装步骤

### 1. Java (控制面)

```bash
# macOS (Homebrew)
brew install openjdk@21

# 验证
java -version
# openjdk version "21.x.x"

# 配置 JAVA_HOME (zsh)
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc
source ~/.zshrc
```

### 2. Python (数据面)

```bash
# macOS (Homebrew)
brew install python@3.12

# 验证
python3 --version
# Python 3.12.x

# 推荐使用虚拟环境
cd data-plane
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 3. Docker

```bash
# macOS: 安装 Docker Desktop
brew install --cask docker

# 验证
docker --version
docker compose version
```

### 4. Ollama (本地 LLM)

```bash
# macOS
brew install ollama

# 启动服务
ollama serve

# 拉取 Qwen2.5 模型（约 4.7 GB）
ollama pull qwen2.5:7b

# 验证
ollama list
```

### 5. ffmpeg (WhisperX 依赖)

```bash
# macOS
brew install ffmpeg

# 验证
ffmpeg -version
```

---

## 环境变量配置

项目根目录提供 `.env.example` 模板，复制后根据实际情况修改：

```bash
cp .env.example .env
```

### 核心环境变量说明

#### Redis 配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `REDIS_URL` | `redis://localhost:6379` | Redis 连接地址 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码（生产环境建议设置） |
| `REDIS_QUEUE_TRANSCRIBE` | `echo:queue:transcribe` | 转写队列名称 |
| `REDIS_QUEUE_SUMMARIZE` | `echo:queue:summarize` | 摘要队列名称 |
| `REDIS_QUEUE_EMBED` | `echo:queue:embed` | 嵌入队列名称 |

#### Neo4j 配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j Bolt 协议地址 |
| `NEO4J_USERNAME` | `neo4j` | 用户名 |
| `NEO4J_PASSWORD` | `echo_neo4j_pass` | 密码（生产环境必须修改） |

#### MinIO 配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MINIO_ENDPOINT` | `localhost:9000` | MinIO API 地址 |
| `MINIO_ACCESS_KEY` | `echo_minio_admin` | 访问密钥 |
| `MINIO_SECRET_KEY` | `echo_minio_secret` | 密钥（生产环境必须修改） |
| `MINIO_SECURE` | `false` | 是否使用 HTTPS |

#### Ollama 配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 服务地址 |
| `OLLAMA_MODEL` | `qwen2.5:7b` | 使用的 LLM 模型 |

#### 音频加密配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AUDIO_ENCRYPT_KEY` | 空 | AES-256-GCM 加密密钥（Base64 编码 32 字节） |

生成密钥：
```bash
openssl rand -base64 32
```

> **重要**：此密钥一旦丢失，加密的音频将无法解密！请安全备份。

#### P2P 同步配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `P2P_ENABLED` | `false` | 是否启用 P2P |
| `P2P_PORT` | `9090` | 监听端口 |
| `P2P_PEERS` | 空 | 对端地址（逗号分隔） |
| `P2P_DEVICE_SECRET` | - | 签名密钥（生产环境必须修改） |
| `P2P_DEVICE_NAME` | `my-echo-device` | 设备名称 |

---

## 本地启动全流程

### 步骤 1：启动基础设施

```bash
# 在项目根目录
docker compose up -d

# 查看服务状态
docker compose ps

# 预期输出：
# echo-redis       running   0.0.0.0:6379->6379/tcp
# echo-neo4j       running   0.0.0.0:7474->7474/tcp, 0.0.0.0:7687->7687/tcp
# echo-minio       running   0.0.0.0:9000->9000/tcp, 0.0.0.0:9001->9001/tcp
# echo-data-plane  running   0.0.0.0:8001->8001/tcp
```

> **首次启动**：Neo4j 和 MinIO 初始化需要约 30 秒，请等待健康检查通过后再进行后续步骤。

### 步骤 2：初始化 Neo4j 索引

```bash
# 手动执行初始化 Cypher（仅需首次执行）
docker exec -it echo-neo4j cypher-shell \
  -u neo4j -p echo_neo4j_pass \
  -f /docker-entrypoint-initdb.d/init.cypher
```

### 步骤 3：启动本地 Ollama

```bash
# 确保 Ollama 服务运行
ollama serve

# 在另一个终端验证模型可用
ollama list
# 应显示 qwen2.5:7b
```

### 步骤 4：启动控制面

```bash
cd control-plane

# 开发模式运行（支持热重载）
./gradlew bootRun

# 验证
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 步骤 5：验证数据面

```bash
# 数据面已在 Docker 中运行，验证健康状态
curl http://localhost:8001/health
# {"status":"healthy","service":"echo-data-plane","version":"0.1.0"}
```

### 步骤 6：端到端测试

```bash
# 上传测试音频
curl -X POST http://localhost:8080/api/v1/audio/upload \
  -F "file=@test.wav" \
  -F "language=zh"

# 查看队列状态
curl http://localhost:8080/api/v1/audio/queues

# 查询人物列表
curl http://localhost:8080/api/v1/graph/persons
```

---

## 开发调试技巧

### 控制面调试

#### 启用 Debug 日志

`application.yml` 中已默认为 `com.echo` 包开启 DEBUG 级别：

```yaml
logging:
  level:
    root: INFO
    com.echo: DEBUG
```

#### 远程调试

```bash
# 以调试模式启动
./gradlew bootRun --debug-jvm
# JVM 将在 5005 端口等待调试器连接
```

在 IntelliJ IDEA 中：Run → Edit Configurations → Add New → Remote JVM Debug → 端口 5005。

#### Neo4j Browser 可视化

访问 http://localhost:7474，使用 `neo4j / echo_neo4j_pass` 登录，可执行 Cypher 查询：

```cypher
// 查看所有人物
MATCH (p:Person) RETURN p LIMIT 25

// 查看社交关系
MATCH (p1:Person)-[r:INTERACTED_WITH]->(p2:Person)
RETURN p1.name, p2.name, r.timestamp

// 查看对话摘要
MATCH (c:Conversation)
WHERE c.status = 'done'
RETURN c.conversationId, c.summary, c.sentiment
ORDER BY c.recordedAt DESC LIMIT 10
```

#### MinIO Console

访问 http://localhost:9001，使用 `echo_minio_admin / echo_minio_secret` 登录，可浏览和下载 Bucket 中的文件。

### 数据面调试

#### 本地运行数据面（非 Docker）

适合需要频繁修改代码的场景：

```bash
cd data-plane
source .venv/bin/activate

# 安装依赖
pip install -r requirements.txt

# 本地启动（需确保 Redis 和 MinIO 已运行）
uvicorn main:app --host 0.0.0.0 --port 8001 --reload
```

> **注意**：本地运行时 `MINIO_ENDPOINT` 和 `REDIS_URL` 应指向 `localhost`，而非 Docker 内部域名。

#### FastAPI 交互式文档

访问 http://localhost:8001/docs 查看 Swagger UI，可直接在浏览器中测试 API。

#### 查看 Redis 队列状态

```bash
# 进入 Redis 容器
docker exec -it echo-redis redis-cli

# 查看队列长度
LLEN echo:queue:transcribe

# 查看任务状态
HGETALL echo:result:<taskId>

# 查看失败队列
LLEN echo:tasks:failed
LRANGE echo:tasks:failed 0 -1
```

#### 模型缓存加速

首次运行时，WhisperX 和 BGE-Small 模型会从 HuggingFace 下载。为避免重复下载：

```bash
# 设置本地缓存目录
export HF_HOME=~/.cache/huggingface

# Docker Compose 已自动挂载此目录
# 查看 docker-compose.yml 中的 volumes 配置
```

---

## 代码规范要求

### Java (控制面)

| 规范 | 说明 |
|------|------|
| 命名 | 类名 PascalCase，方法/变量 camelCase，常量 UPPER_SNAKE_CASE |
| 注释 | Javadoc 格式，中文描述 + 英文术语 |
| 异常 | 使用 `EchoException` + `ErrorCode` 枚举，禁止裸抛 RuntimeException |
| 响应 | 所有 API 返回 `ApiResponse<T>` 统一封装 |
| 配置 | 敏感值通过环境变量注入，不硬编码在代码中 |
| 编码 | UTF-8（`build.gradle` 中已配置 `options.encoding = 'UTF-8'`） |

### Python (数据面)

| 规范 | 说明 |
|------|------|
| 风格 | 遵循 PEP 8，使用 type hints |
| 模型 | 使用 Pydantic v2 语法定义请求/响应模型 |
| 日志 | 使用 `logging` 模块，不记录敏感数据明文 |
| 异步 | I/O 密集操作使用 `async/await`，CPU 密集操作使用 `run_in_executor` |
| 配置 | 通过 `pydantic-settings` 读取环境变量，使用 `@lru_cache` 单例 |
| 隐私 | 日志中仅记录元数据（taskId, conversationId），不记录音频/摘要内容 |

### Git 规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
feat: 新增 P2P 快照签名验证
fix: 修复 Redis 队列消费超时异常
docs: 更新架构设计文档
refactor: 重构 LLM 服务提示词构建
test: 添加 AudioService 单元测试
chore: 升级 Spring Boot 至 3.2.5
```

### 分支策略

| 分支 | 用途 |
|------|------|
| `main` | 稳定发布版本 |
| `develop` | 开发集成分支 |
| `feature/*` | 功能开发分支 |
| `hotfix/*` | 紧急修复分支 |
