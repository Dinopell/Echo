# 架构设计文档

> Project Echo 采用 **控制面 / 数据面分离** 的双进程架构，通过 Redis 队列实现松耦合的异步通信，确保所有 AI 推理在本地完成，零云依赖。

---

## 分层架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                           表现层 (Presentation)                      │
│   Mobile App / Web Dashboard / CLI — 通过 REST API 与系统交互        │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                      控制面 — 编排层 (Orchestration)                  │
│                                                                      │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐  │
│  │  Audio  │ │  Task   │ │  Graph   │ │   P2P    │ │  Callback  │  │
│  │Controller│ │Controller│ │Controller│ │Controller│ │Controller  │  │
│  └────┬────┘ └────┬────┘ └────┬─────┘ └────┬─────┘ └─────┬──────┘  │
│       │           │           │             │             │          │
│  ┌────▼───────────▼───────────▼─────────────▼─────┐  ┌───▼────────┐ │
│  │            Service 层 (业务逻辑)                 │  │ Exception  │ │
│  │  AudioService / TaskQueueService / GraphService │  │  Handler   │ │
│  │  P2PSyncService                                 │  └────────────┘ │
│  └────────────────────┬────────────────────────────┘                  │
│                       │                                              │
│  ┌────────────────────▼────────────────────────────┐                 │
│  │         基础设施层 (Infrastructure)               │                 │
│  │  MinIO Client / Redis Template / Neo4j OGM      │                 │
│  └─────────────────────────────────────────────────┘                 │
└──────────────────────────────────────────────────────────────────────┘
                               │
                    Redis BRPOP / LPUSH
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                      数据面 — 推理层 (Inference)                      │
│                                                                      │
│  ┌──────────────┐ ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │  WhisperX    │ │   Qwen2.5   │ │  BGE-Small   │ │   Image      │ │
│  │  Service     │ │   Service   │ │  Service      │ │  Service     │ │
│  │  (语音转写)  │ │  (LLM 摘要) │ │  (向量嵌入)   │ │  (记忆卡片)  │ │
│  └──────────────┘ └─────────────┘ └──────────────┘ └──────────────┘ │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────────┐│
│  │              Task Consumer (Redis 队列消费 + 回调控制面)          ││
│  └──────────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────────┘
                               │
                    HTTP Callback (内网)
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                     存储层 (Storage)                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐ │
│  │  Redis   │  │  Neo4j   │  │  MinIO   │  │  Ollama (本地模型)    │ │
│  │  队列/缓存│  │  知识图谱 │  │  对象存储 │  │  LLM 推理引擎        │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 控制面职责与接口

控制面基于 **Spring Boot 3.2 + Java 17**，承担 API 网关、任务编排和图谱管理三大职责。

### 核心职责

| 职责 | 说明 |
|------|------|
| API 网关 | 对外暴露 REST API，统一认证鉴权 |
| 任务编排 | 将音频上传 → 转写 → 摘要流程编排为 Redis 队列任务 |
| 图谱管理 | 维护 Neo4j 中 Person / Conversation 节点和 INTERACTED_WITH 关系 |
| 文件管理 | 音频 AES-256-GCM 加密后存入 MinIO，生成预签名 URL |
| P2P 同步 | 局域网设备间加密快照传输与合并 |
| 结果回收 | 接收数据面回调，更新 Redis 任务状态和 Neo4j 图谱 |

### 控制面不直接做的事

- **不调用 AI 模型**：所有 AI 推理（转写、摘要、嵌入、卡片生成）由数据面完成
- **不直接写向量**：嵌入向量由数据面生成，后续通过回调写入
- **不存储音频明文**：音频上传后立即 AES-256-GCM 加密，只有配置了密钥才能解密

### 关键 Controller

| Controller | 路径前缀 | 职责 |
|-----------|---------|------|
| `AudioController` | `/api/v1/audio` | 音频上传、加密存储、任务触发 |
| `TaskController` | `/api/v1/tasks` | 任务查询、手动投递 |
| `GraphController` | `/api/v1/graph` | 图谱 CRUD、多维查询 |
| `P2PSyncController` | `/api/v1/sync` | P2P 状态、触发同步、接收快照 |
| `CallbackController` | `/api/callback` | 数据面结果回调（内网专用） |

---

## 数据面职责与接口

数据面基于 **FastAPI + Python 3.11**，专注 AI 推理，不直接操作 Neo4j。

### 核心职责

| 职责 | 说明 |
|------|------|
| 语音转写 | WhisperX 本地推理，返回词级时间戳 + 说话人标注 |
| 文本摘要 | Qwen2.5 (via Ollama) 生成结构化摘要 + 情感分析 |
| 向量嵌入 | BAAI/bge-small-zh-v1.5 生成 512 维语义向量 |
| 记忆卡片 | 根据情感峰值生成视觉卡片 (ComfyUI / SVG 降级) |
| 晨间胶囊 | 汇总前一天对话，生成每日记忆精华 |
| 队列消费 | BRPOP 监听 Redis 队列，自动执行完整流水线 |

### 关键 Router

| Router | 路径前缀 | 职责 |
|--------|---------|------|
| `transcribe` | `/api/v1/transcribe` | 语音转写 |
| `summarize` | `/api/v1/summarize` | 文本摘要 + 晨间胶囊 |
| `generate_card` | `/api/v1/generate-card` | 记忆卡片生成 |
| `embed` | `/api/v1/embed` | 向量嵌入 |

### TaskConsumer 自动流水线

数据面的 `TaskConsumer` 在启动时自动监听 Redis 转写队列，执行以下完整流水线：

```
BRPOP 消费任务
    │
    ├── 1. 下载音频 → WhisperX 转写
    │
    ├── 2. Qwen2.5 摘要 + 情感分析 + 话题提取
    │
    ├── 3. 情感峰值检测 (|score| ≥ 0.8)
    │       └── 触发 → 视觉记忆卡片生成
    │
    ├── 4. 向量嵌入 (BGE-Small)
    │
    └── 5. HTTP POST 回调控制面 /api/callback/transcribe-result
```

---

## 数据流详解

### 从音频采集到记忆卡片的完整流程

```
客户端                  控制面                      Redis                数据面                  存储
  │                      │                          │                    │                      │
  │  POST /audio/upload  │                          │                    │                      │
  │─────────────────────>│                          │                    │                      │
  │                      │  1. 校验文件格式          │                    │                      │
  │                      │  2. AES-256-GCM 加密     │                    │                      │
  │                      │──────────────────────────────────────────────────────────────────────>│
  │                      │  3. 存入 MinIO raw-audio │                    │        MinIO          │
  │                      │  4. 创建 Neo4j 节点      │                    │                      │
  │                      │──────────────────────────────────────────────────────────────────────>│
  │                      │                          │        Neo4j       │                      │
  │                      │  5. LPUSH 转写任务       │                    │                      │
  │                      │─────────────────────────>│                    │                      │
  │  返回 taskId         │                          │                    │                      │
  │<─────────────────────│                          │                    │                      │
  │                      │                          │  6. BRPOP 消费     │                      │
  │                      │                          │───────────────────>│                      │
  │                      │                          │                    │  7. 从 MinIO 下载音频 │
  │                      │                          │                    │<─────────────────────│
  │                      │                          │                    │  8. WhisperX 转写    │
  │                      │                          │                    │  9. Qwen2.5 摘要     │
  │                      │                          │                    │ 10. 情感峰值→卡片生成 │
  │                      │                          │                    │ 11. BGE 向量嵌入     │
  │                      │                          │                    │                      │
  │                      │  12. POST /callback      │                    │                      │
  │                      │<──────────────────────────────────────────────│                      │
  │                      │ 13. 更新 Redis 状态      │                    │                      │
  │                      │─────────────────────────>│                    │                      │
  │                      │ 14. 更新 Neo4j 图谱      │                    │                      │
  │                      │──────────────────────────────────────────────────────────────────────>│
  │                      │                          │                    │        Neo4j          │
  │                      │                          │                    │                      │
  │  GET /audio/task/id  │                          │                    │                      │
  │─────────────────────>│                          │                    │                      │
  │  返回处理结果        │                          │                    │                      │
  │<─────────────────────│                          │                    │                      │
```

### 步骤说明

1. **音频上传**：客户端上传 wav/mp3/m4a 音频文件，指定语言 (auto/zh/en)
2. **加密存储**：控制面使用 AES-256-GCM 加密音频内容，存入 MinIO `raw-audio` bucket
3. **创建图谱节点**：在 Neo4j 创建 `Conversation` 节点，关联 `Person` 节点
4. **入队任务**：将任务 JSON 推入 Redis `echo:queue:transcribe` 队列
5. **异步消费**：数据面 TaskConsumer 通过 BRPOP 获取任务
6. **AI 推理流水线**：依次执行转写 → 摘要 → 峰值卡片 → 嵌入
7. **结果回调**：数据面 HTTP POST 回调控制面，携带完整处理结果
8. **状态更新**：控制面更新 Redis 任务状态和 Neo4j 图谱数据

---

## Redis 队列通信协议

### 队列 Key 命名

| Key | 类型 | 用途 |
|-----|------|------|
| `echo:queue:transcribe` | List | 转写任务队列（控制面 LPUSH，数据面 BRPOP） |
| `echo:queue:summarize` | List | 摘要任务队列（预留，当前由流水线内联处理） |
| `echo:queue:embed` | List | 嵌入任务队列（预留，当前由流水线内联处理） |
| `echo:result:{taskId}` | Hash | 任务状态与结果（24 小时 TTL） |
| `echo:tasks:failed` | List | 失败任务队列（供人工排查） |

### 转写任务消息格式

控制面 LPUSH 到 `echo:queue:transcribe` 的 JSON 消息：

```json
{
  "taskId": "uuid-string",
  "conversationId": "uuid-string",
  "audioObjectKey": "audio/2024/01/15/xxx.enc.wav",
  "bucket": "raw-audio",
  "language": "auto",
  "createdAt": "2024-01-15T10:30:00",
  "status": "pending",
  "priority": 0
}
```

### 任务状态 Hash 结构

Key: `echo:result:{taskId}`

| Field | 值 | 说明 |
|-------|-----|------|
| `status` | `pending` / `processing` / `done` / `failed` | 任务状态 |
| `task_id` | UUID | 任务 ID |
| `transcript_length` | 数字 | 转写文本长度（完成后填充） |
| `sentiment` | `positive` / `negative` / `neutral` | 情感标签 |
| `sentiment_score` | 浮点数字符串 | 情感分数 |
| `error` | 错误描述 | 失败时填充 |

TTL：24 小时自动过期。

### 失败任务格式

Key: `echo:tasks:failed`，LPUSH 的 JSON 消息：

```json
{
  "original_task": "{原始任务JSON字符串}",
  "error": "错误描述",
  "failed_at": "2024-01-15T10:35:00"
}
```

---

## 存储设计

### MinIO Bucket 规划

| Bucket | 用途 | 文件格式 | 访问方式 |
|--------|------|---------|---------|
| `raw-audio` | 原始音频文件（加密后） | `audio/{yyyy}/{MM}/{dd}/{uuid}.enc.{ext}` | 预签名 URL (1~168h) |
| `transcriptions` | 转写结果 JSON | `transcription/{conversationId}.json` | 内部服务直读 |
| `memory-cards` | AI 生成的记忆卡片图片 | `card/{conversationId}/{style}_{uuid}.svg` | 预签名 URL (7 天) |
| `snapshots` | P2P 同步快照文件 | `snapshot/{deviceName}_{timestamp}.echo` | 内部服务直读 |

### 访问策略

- 所有 Bucket 默认 **私有策略**（`mc anonymous set none`），符合隐私优先原则
- 外部访问通过 **预签名 URL**（MinIO Presigned URL），可设置有效期
- 音频文件在写入前经过 **AES-256-GCM 加密**，即使 Bucket 泄露也无法还原

### Neo4j 图模型

```
(Person) ──[INTERACTED_WITH]──> (Person)
    │                              │
    └──[PARTICIPATED_IN]──> (Conversation) <──[PARTICIPATED_IN]──┘
```

#### Person 节点

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | String | 人物姓名（唯一约束） |
| `relationship` | String | 关系标签（如：同事、家人） |
| `mentionCount` | Integer | 被提及次数 |
| `lastSeen` | LocalDateTime | 最后见面时间 |

#### Conversation 节点

| 属性 | 类型 | 说明 |
|------|------|------|
| `conversationId` | String | 对话唯一 ID（唯一约束） |
| `transcript` | String | 转写全文 |
| `summary` | String | AI 摘要 |
| `status` | String | 处理状态 (pending/processing/done/failed) |
| `sentiment` | String | 情感倾向 |
| `recordedAt` | LocalDateTime | 录制时间 |
| `taskId` | String | 关联的 Redis 任务 ID |
| `embeddingVector` | float[] | 512 维语义向量 |

#### INTERACTED_WITH 关系

| 属性 | 类型 | 说明 |
|------|------|------|
| `conversationId` | String | 发生交互的对话 ID |
| `timestamp` | LocalDateTime | 交互时间 |

### Neo4j 索引设计

| 类型 | 名称 | 目标 |
|------|------|------|
| 唯一约束 | `person_name_unique` | `Person.name` |
| 唯一约束 | `conversation_id_unique` | `Conversation.conversationId` |
| 普通索引 | `person_mention_count` | `Person.mentionCount` |
| 普通索引 | `person_last_seen` | `Person.lastSeen` |
| 普通索引 | `conversation_recorded_at` | `Conversation.recordedAt` |
| 普通索引 | `conversation_status` | `Conversation.status` |
| 普通索引 | `conversation_task_id` | `Conversation.taskId` |
| 全文索引 | `conversation_text_search` | `Conversation.transcript, summary` |
| 向量索引 | `conversation_embedding` | `Conversation.embeddingVector` (512维, cosine) |

---

## P2P 网格架构说明

### 设计原则

- **仅限局域网**：所有 P2P 通信只在局域网内完成，不经过互联网
- **签名验证**：快照使用 SHA-256 签名，防止伪造
- **MERGE 语义**：接收方使用 MERGE 策略合并节点，避免重复数据

### 同步流程

```
设备 A                         设备 B
  │                              │
  │  GET /api/v1/sync/status     │
  │<─────────────────────────────│
  │                              │
  │  POST /api/v1/sync/trigger   │
  │  { peerAddress: "B:8080" }   │
  │─────────────────────────────>│
  │                              │
  │  1. 导出本地图谱为 JSON 快照  │
  │  2. SHA-256 签名             │
  │  3. 上传快照到 MinIO         │
  │                              │
  │  POST /api/v1/sync/receive   │
  │  { snapshot, signature,      │
  │    sourcePeer }              │
  │─────────────────────────────>│
  │                              │
  │                              │  4. 验证签名
  │                              │  5. 解析快照 JSON
  │                              │  6. MERGE 人物节点
  │                              │  7. MERGE 对话节点
  │                              │
  │     返回合并结果              │
  │<─────────────────────────────│
```

### 配置参数

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `P2P_ENABLED` | `false` | 是否启用 P2P 同步 |
| `P2P_PORT` | `9090` | 本设备监听端口 |
| `P2P_PEERS` | 空 | 对端设备地址（逗号分隔） |
| `P2P_DEVICE_SECRET` | - | 设备签名密钥（生产环境必须修改） |
| `P2P_DEVICE_NAME` | `my-echo-device` | 设备名称标识 |

---

## 隐私设计原则

1. **零云依赖**：所有服务地址均指向 `localhost` 或局域网 IP，无任何第三方云服务地址
2. **本地推理**：WhisperX、Qwen2.5、BGE-Small 全部在本地 CPU/GPU 运行
3. **加密存储**：音频文件 AES-256-GCM 加密后才写入 MinIO，密钥由用户掌控
4. **私有访问**：MinIO 所有 Bucket 设置为私有策略，仅通过预签名 URL 访问
5. **内网回调**：数据面 → 控制面的回调仅在内网进行，可配置 IP 白名单
6. **日志脱敏**：数据面日志不记录音频内容和摘要明文，仅记录元数据
