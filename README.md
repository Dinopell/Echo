# Project Echo

**隐私优先的边缘计算智能伴侣** — 在本地设备上聆听、理解并记忆你的生活，所有数据永远不出你的家门。

---

## 核心特性

| 特性 | 说明 |
|------|------|
| 🎙️ 智能聆听 | 本地 WhisperX 语音转写，支持词级时间戳与说话人分离 |
| 🕸️ 社交图谱 | Neo4j 知识图谱，自动构建人物关系网络 |
| 🎴 创意回想 | 情感峰值自动触发视觉记忆卡片，每日晨间胶囊唤醒记忆 |
| 🔒 隐私优先 | 全栈本地运行，零云依赖，AES-256-GCM 端到端加密 |
| 🌐 P2P 分布式 | 局域网设备间加密同步，SHA-256 签名验证 |

## 技术架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         客户端 (Mobile / Web)                       │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTP / REST
┌──────────────────────────────▼──────────────────────────────────────┐
│                    控制面 — Spring Boot (port 8080)                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌─────────┐ │
│  │  Audio   │ │  Task    │ │  Graph   │ │   P2P     │ │Callback │ │
│  │Controller│ │Controller│ │Controller│ │Controller │ │Controller│ │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └─────┬─────┘ └────┬────┘ │
│       │            │            │              │            │       │
│  ┌────▼────┐  ┌────▼────┐  ┌───▼───┐   ┌─────▼────┐  ┌───▼────┐  │
│  │  Audio  │  │  Task   │  │ Graph │   │   P2P    │  │  Task  │  │
│  │ Service │  │  Queue  │  │Service│   │  Service │  │  Queue │  │
│  │         │  │ Service │  │       │   │          │  │ Service│  │
│  └────┬────┘  └────┬────┘  └───┬───┘   └─────┬────┘  └───┬────┘  │
└───────┼────────────┼───────────┼─────────────┼──────────┼────────┘
        │            │           │             │          │
   ┌────▼────┐  ┌────▼────┐ ┌───▼───┐  ┌─────▼────┐     │
   │  MinIO  │  │  Redis  │ │ Neo4j │  │  MinIO   │     │
   │ (Audio) │  │ (Queue) │ │(Graph)│  │(Snapshot)│     │
   └─────────┘  └────┬────┘ └───────┘  └──────────┘     │
                     │ LPUSH / BRPOP                      │
┌────────────────────▼────────────────────────────────────▼────────┐
│                  数据面 — FastAPI (port 8001)                      │
│  ┌────────────┐ ┌───────────┐ ┌─────────────┐ ┌──────────────┐  │
│  │ WhisperX   │ │  Qwen2.5  │ │  BGE-Small  │ │  Image       │  │
│  │ (转写)     │ │  (摘要)   │ │  (向量嵌入)  │ │  Service     │  │
│  └────────────┘ └───────────┘ └─────────────┘ └──────────────┘  │
│                           HTTP Callback ──────────────────────────┘
```

## 技术栈

| 层级 | 技术 | 用途 |
|------|------|------|
| 控制面 | Java 17 + Spring Boot 3.2 | API 网关、任务编排、图谱管理 |
| 数据面 | Python 3.11 + FastAPI | AI 推理：转写 / 摘要 / 嵌入 / 卡片 |
| 语音识别 | WhisperX | 本地语音转写 + 说话人分离 |
| 大语言模型 | Qwen2.5 (via Ollama) | 本地 LLM 摘要 / 情感分析 |
| 向量嵌入 | BAAI/bge-small-zh-v1.5 | 中文语义向量 (512 维) |
| 知识图谱 | Neo4j 5 Community | 人物关系 + 对话图谱 |
| 消息队列 | Redis 7 | 控制面 ↔ 数据面任务通信 |
| 对象存储 | MinIO | 音频 / 转写 / 卡片 / 快照 |
| 容器化 | Docker Compose | 一键部署基础设施 |

## 快速开始

### Prerequisites

- Java 17+
- Python 3.11+
- Docker & Docker Compose
- [Ollama](https://ollama.ai) (运行本地 LLM)
- 8GB+ RAM (推荐 16GB)

### 启动步骤

```bash
# 1. 克隆项目
git clone <repo-url> && cd Echo

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env 设置密码和密钥（生产环境必须修改）

# 3. 启动基础设施 (Redis + Neo4j + MinIO + 数据面)
docker compose up -d

# 4. 启动本地 Ollama 并拉取模型
ollama serve
ollama pull qwen2.5:7b

# 5. 启动控制面
cd control-plane
./gradlew bootRun

# 6. 验证服务
curl http://localhost:8080/actuator/health    # 控制面
curl http://localhost:8001/health              # 数据面
```

## 项目目录结构

```
Echo/
├── control-plane/                 # Spring Boot 控制面
│   ├── src/main/java/com/echo/
│   │   ├── config/               # 配置类 (MinIO, Neo4j, Redis, Security)
│   │   ├── controller/           # REST 控制器
│   │   ├── dto/                  # 数据传输对象
│   │   ├── exception/            # 异常处理
│   │   ├── model/                # Neo4j 实体模型
│   │   ├── repository/           # Neo4j Repository
│   │   └── service/              # 业务服务层
│   └── src/main/resources/
│       └── application.yml       # Spring Boot 配置
├── data-plane/                    # FastAPI 数据面
│   ├── models/schemas.py         # Pydantic 数据模型
│   ├── routers/                  # API 路由 (转写/摘要/卡片/嵌入)
│   ├── services/                 # AI 服务 (WhisperX/LLM/Embedding/Image)
│   ├── utils/                    # 工具 (加密/音频处理)
│   ├── workers/task_consumer.py  # Redis 队列消费者
│   ├── config.py                 # 配置管理
│   ├── main.py                   # FastAPI 入口
│   └── Dockerfile
├── infrastructure/                # 基础设施初始化
│   ├── minio/init.sh             # MinIO Bucket 初始化
│   └── neo4j/init.cypher         # Neo4j 索引/约束初始化
├── docs/                          # 项目文档
├── docker-compose.yml             # 基础设施编排
├── .env.example                   # 环境变量模板
└── README.md
```

## API 接口概览

### 控制面 (http://localhost:8080)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/audio/upload` | 上传音频并触发转写流水线 |
| GET | `/api/v1/audio/url` | 获取音频预签名访问 URL |
| GET | `/api/v1/audio/task/{taskId}` | 查询任务处理进度 |
| POST | `/api/v1/tasks/transcribe` | 手动投递转写任务 |
| GET | `/api/v1/graph/persons` | 获取人物列表 |
| GET | `/api/v1/graph/query` | 多条件图谱查询 |
| POST | `/api/v1/sync/trigger` | 触发 P2P 同步 |
| POST | `/api/callback/transcribe-result` | 接收数据面转写回调 |

### 数据面 (http://localhost:8001)

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/transcribe` | 语音转写 (WhisperX) |
| POST | `/api/v1/summarize` | 文本摘要 (Qwen2.5) |
| POST | `/api/v1/generate-card` | 生成记忆卡片 |
| POST | `/api/v1/embed` | 向量嵌入 (BGE) |
| POST | `/api/v1/morning-capsule` | 生成晨间胶囊 |

> 完整 API 文档请参阅 [docs/api-reference.md](docs/api-reference.md)

## 开发指南

- [架构设计](docs/architecture.md) — 分层架构、数据流、通信协议
- [开发环境搭建](docs/development-guide.md) — 环境配置、调试技巧、代码规范
- [API 接口文档](docs/api-reference.md) — 完整请求/响应示例
- [部署指南](docs/deployment.md) — 生产部署、备份恢复、故障排查

## 路线图

### Phase 1 — 核心管线 (当前)
- [x] Spring Boot 控制面基础架构
- [x] FastAPI 数据面 AI 推理服务
- [x] Redis 队列异步任务通信
- [x] MinIO 加密音频存储
- [x] Neo4j 知识图谱建模

### Phase 2 — 智能图谱
- [ ] 语义向量搜索 (Neo4j Vector Index)
- [ ] 自动人物关系推断
- [ ] 时间线视图与话题聚类
- [ ] 晨间胶囊定时生成

### Phase 3 — 分布式协同
- [ ] P2P 局域网加密同步
- [ ] 多设备快照合并策略
- [ ] 设备发现与认证

### Phase 4 — 体验升级
- [ ] 移动端客户端 (Flutter)
- [ ] 实时流式语音识别
- [ ] 多模态记忆卡片 (ComfyUI 集成)
- [ ] 本地 TTS 语音回顾

## 贡献指引

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'feat: 添加某功能'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 发起 Pull Request

### Commit 规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
feat: 新功能
fix: 修复 bug
docs: 文档更新
refactor: 代码重构
test: 测试相关
chore: 构建/工具变更
```

## License

MIT License — 详见 [LICENSE](LICENSE) 文件
