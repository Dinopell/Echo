# API 接口文档

> Project Echo 的 API 分为**控制面** (Spring Boot, port 8080) 和**数据面** (FastAPI, port 8001) 两部分。控制面对外暴露业务接口，数据面主要被内部调用。

---

## 通用响应格式

### 控制面响应

所有控制面接口返回统一的 `ApiResponse` 结构：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 业务状态码（200=成功，400=参数错误，500=服务内部错误） |
| `message` | string | 提示消息 |
| `data` | T | 业务数据（失败时可为 null） |

### 数据面响应

数据面返回各接口独立的 Pydantic 模型，通常包含 `status` 字段：

```json
{
  "status": "success",
  "message": "...",
  ...
}
```

---

## 控制面 API

Base URL: `http://localhost:8080`

> **JWT 认证**：当环境变量 `JWT_ENABLED=true` 时，除 `/api/callback/**`、`/api/v1/auth/**` 和 `/actuator/health` 外的所有 `/api/**` 接口均需在请求头携带 `Authorization: Bearer <token>`。Token 通过 `POST /api/v1/auth/token` 换取。

### 认证 — `/api/v1/auth`

#### POST `/api/v1/auth/token`

使用 API Key 换取 JWT Bearer Token。

**请求体**

```json
{
  "apiKey": "your_echo_api_key"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `apiKey` | String | 是 | 与 `ECHO_API_KEY` 环境变量匹配 |

**响应示例**（JWT 已启用）

```json
{
  "code": 200,
  "message": "Token 签发成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

**响应示例**（JWT 未启用，`JWT_ENABLED=false`）

```json
{
  "code": 200,
  "message": "JWT 认证未启用，无需 Token 即可访问 API",
  "data": {
    "accessToken": "",
    "tokenType": "Bearer",
    "expiresIn": 0
  }
}
```

**使用方式**

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

---

#### GET `/api/v1/auth/status`

查询 JWT 认证是否启用。

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "enabled": true,
    "expiresInSeconds": 86400
  }
}
```

---

### 音频管理 — `/api/v1/audio`

#### POST `/api/v1/audio/upload`

上传音频文件，触发转写流水线。

**请求**

- Content-Type: `multipart/form-data`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | File | 是 | 音频文件（支持 wav/mp3/m4a） |
| `language` | String | 否 | 语言提示：`auto`（默认）/ `zh` / `en` |

**响应示例**

```json
{
  "code": 200,
  "message": "音频已加密上传，转写任务已入队",
  "data": {
    "conversationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "taskId": "t1a2b3c4-d5e6-7890-abcd-ef1234567890",
    "objectKey": "audio/2024/01/15/a1b2c3d4.enc.wav",
    "status": "pending",
    "message": "转写任务已入队，请通过 taskId 查询进度"
  }
}
```

**处理流程**：参数校验 → AES-256-GCM 加密 → 存入 MinIO `raw-audio` → 创建 Neo4j 节点 → LPUSH Redis 转写队列

---

#### GET `/api/v1/audio/url`

获取音频文件预签名访问 URL。

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `objectKey` | String | 是 | MinIO 对象键 |
| `expireHours` | int | 否 | 有效期（小时），默认 1，范围 1~168 |

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "url": "http://localhost:9000/raw-audio/audio/...?X-Amz-Algorithm=...",
    "objectKey": "audio/2024/01/15/a1b2c3d4.enc.wav"
  }
}
```

---

#### GET `/api/v1/audio/task/{taskId}`

查询任务处理状态。

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "status": "done",
    "task_id": "t1a2b3c4-d5e6-7890-abcd-ef1234567890",
    "transcript_length": 1234,
    "sentiment": "positive",
    "sentiment_score": "0.72"
  }
}
```

**任务状态**：`pending` → `processing` → `done` / `failed`

---

#### GET `/api/v1/audio/queues`

查看所有队列待处理任务数量（调试/监控用）。

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "echo:queue:transcribe": 3,
    "echo:queue:summarize": 0,
    "echo:queue:embed": 0
  }
}
```

---

### 任务管理 — `/api/v1/tasks`

#### POST `/api/v1/tasks/transcribe`

手动投递转写任务到 Redis 队列。适用于音频文件已通过其他方式上传到 MinIO 的场景。

**请求体**

```json
{
  "audioObjectKey": "audio/2024/01/15/xxx.enc.wav",
  "bucket": "raw-audio",
  "language": "auto",
  "conversationId": "optional-conversation-id",
  "priority": 0
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `audioObjectKey` | String | 是 | MinIO 中的音频文件路径 |
| `bucket` | String | 否 | Bucket 名称，默认 `raw-audio` |
| `language` | String | 否 | 语言：`auto` / `zh` / `en`，默认 `auto` |
| `conversationId` | String | 否 | 关联对话 ID，不填则自动生成 |
| `priority` | Integer | 否 | 优先级：0=普通，1=高优先，默认 0 |

**响应示例**

```json
{
  "code": 200,
  "message": "转写任务已入队",
  "data": {
    "taskId": "t1a2b3c4-d5e6-7890-abcd-ef1234567890",
    "status": "pending"
  }
}
```

---

#### GET `/api/v1/tasks/{taskId}`

查询单个任务状态。响应格式同 `/api/v1/audio/task/{taskId}`。

---

#### GET `/api/v1/tasks/queues`

查询所有队列统计信息。响应格式同 `/api/v1/audio/queues`。

---

### 知识图谱 — `/api/v1/graph`

#### GET `/api/v1/graph/persons`

获取所有人物节点列表（按互动次数降序）。

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "name": "张三",
      "relationship": "同事",
      "mentionCount": 15,
      "lastSeen": "2024-01-15T10:30:00"
    }
  ]
}
```

---

#### GET `/api/v1/graph/persons/important`

获取重要人物列表（按提及频率排序）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `limit` | int | 否 | 返回数量，默认 20，范围 1~100 |

---

#### GET `/api/v1/graph/persons/{name}/network`

获取指定人物的社交关系网络。

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "name": "李四",
      "relationship": "朋友",
      "mentionCount": 8
    }
  ]
}
```

---

#### POST `/api/v1/graph/persons`

手动创建或更新人物节点。

**请求体**

```json
{
  "name": "王五",
  "relationship": "邻居"
}
```

**响应示例**

```json
{
  "code": 200,
  "message": "人物节点已保存",
  "data": {
    "name": "王五",
    "relationship": "邻居",
    "mentionCount": 0
  }
}
```

---

#### GET `/api/v1/graph/query`

多条件图谱查询，支持按人名、时间范围、话题组合查询。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `personName` | String | 否 | 按人名模糊查询 |
| `startTime` | String | 否 | 起始时间 ISO-8601 |
| `endTime` | String | 否 | 截止时间 ISO-8601 |
| `topic` | String | 否 | 话题关键词 |
| `limit` | int | 否 | 返回数量上限，默认 20 |

> 多个条件同时指定时，优先级：`personName` > `topic` > 时间范围

**请求示例**

```
GET /api/v1/graph/query?personName=张三&limit=10
GET /api/v1/graph/query?topic=项目进度&startTime=2024-01-01T00:00:00
```

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "conversationId": "a1b2c3d4-...",
      "transcript": "今天讨论了...",
      "summary": "关于项目进度的讨论...",
      "status": "done",
      "sentiment": "positive",
      "recordedAt": "2024-01-15T10:30:00"
    }
  ]
}
```

---

#### GET `/api/v1/graph/conversations`

获取近期对话列表。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `days` | int | 否 | 最近几天，默认 7，范围 1~365 |

---

#### POST `/api/v1/graph/search/semantic`

语义向量搜索：将自然语言查询转为嵌入向量，通过 Neo4j Vector Index 召回相似对话。

**处理流程**：查询文本 → 数据面 BGE 嵌入 → Neo4j `conversation_embedding` 索引检索

**请求体**

```json
{
  "query": "上周和张三讨论的项目进度",
  "limit": 10,
  "minScore": 0.5
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | String | 是 | 自然语言查询文本 |
| `limit` | int | 否 | 返回数量，默认 10，范围 1~50 |
| `minScore` | float | 否 | 最低余弦相似度（0~1），默认 0.5 |

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "conversation": {
        "conversationId": "a1b2c3d4-...",
        "summary": "关于项目进度的讨论...",
        "sentiment": "positive",
        "status": "done",
        "recordedAt": "2024-01-15T10:30:00"
      },
      "score": 0.87
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `score` | 余弦相似度分数，越高表示与查询越相关 |

**前置条件**

- Neo4j 已执行 `init.cypher` 创建向量索引
- 目标对话已写入 `embeddingVector`（转写流水线完成后自动填充）
- 数据面（8001）可访问，用于生成查询向量

---

#### GET `/api/v1/graph/search/semantic`

语义向量搜索（GET 便捷方式），参数与 POST 请求体字段相同。

**请求示例**

```
GET /api/v1/graph/search/semantic?query=项目进度&limit=10&minScore=0.5
```

响应格式同 POST。

---

#### PUT `/api/v1/graph/conversations/{conversationId}`

更新对话处理结果（数据面回调使用）。

**请求体**

```json
{
  "transcript": "转写全文...",
  "summary": "AI 生成的摘要...",
  "status": "done"
}
```

---

### P2P 同步 — `/api/v1/sync`

#### GET `/api/v1/sync/status`

查询 P2P 同步当前状态。

**响应示例**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "enabled": true,
    "deviceName": "my-echo-device",
    "connectedPeers": 1,
    "lastSyncTime": "2024-01-15T10:30:00"
  }
}
```

---

#### POST `/api/v1/sync/trigger`

触发手动同步（向对端发送本地快照）。

**请求体**

```json
{
  "peerAddress": "192.168.1.100:8080"
}
```

---

#### POST `/api/v1/sync/receive`

接收对端发来的 .echo 快照并合并到本地图谱。

**请求体**

```json
{
  "snapshot": "{快照JSON字符串}",
  "signature": "sha256-signature-hex",
  "sourcePeer": "other-echo-device"
}
```

**响应示例**

```json
{
  "code": 200,
  "message": "快照合并完成",
  "data": {
    "mergedPersons": 5,
    "mergedConversations": 12,
    "skippedDuplicates": 3
  }
}
```

---

#### GET `/api/v1/sync/discover`

发现局域网内的 Echo 设备。

---

### 数据面回调 — `/api/callback`

> 此组接口仅供数据面内部调用，禁止外部访问。

#### POST `/api/callback/transcribe-result`

接收数据面转写完成回调。

**请求体**

```json
{
  "taskId": "t1a2b3c4-...",
  "conversationId": "a1b2c3d4-...",
  "status": "done",
  "transcript": "完整转写文本...",
  "summary": "AI 生成的摘要...",
  "sentiment": "positive",
  "sentiment_score": 0.72,
  "participants": "张三,李四",
  "key_persons": ["张三", "李四"],
  "key_topics": ["项目进度", "技术方案"],
  "card_object_key": "card/a1b2c3d4/ambient_xxx.svg",
  "card_url": "http://localhost:9000/memory-cards/...",
  "embedding": [0.012, -0.034, ...],
  "processed_at": "2024-01-15T10:35:00"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `taskId` | String | 是 | 任务 ID |
| `conversationId` | String | 是 | 对话 ID |
| `status` | String | 是 | `done` / `failed` |
| `transcript` | String | 成功时必填 | 转写全文 |
| `summary` | String | 否 | 摘要 |
| `sentiment` | String | 否 | 情感倾向 |
| `sentiment_score` | float | 否 | 情感分数 |
| `participants` | String | 否 | 逗号分隔的参与者 |
| `error_message` | String | 失败时填写 | 错误详情 |

---

#### POST `/api/callback/summarize-result`

接收数据面摘要完成回调。

**请求体**

```json
{
  "taskId": "t1a2b3c4-...",
  "conversationId": "a1b2c3d4-...",
  "status": "done",
  "summary": "摘要内容...",
  "sentiment": "neutral"
}
```

---

#### POST `/api/callback/embed-result`

接收向量嵌入完成回调（预留接口，当前仅记录日志）。

---

## 数据面 API

Base URL: `http://localhost:8001`

### 语音转写 — `/api/v1/transcribe`

#### POST `/api/v1/transcribe`

从 MinIO 读取音频文件，使用本地 WhisperX 模型进行转写。

**请求体**

```json
{
  "audio_object_key": "audio/2024/01/15/xxx.enc.wav",
  "bucket": "raw-audio",
  "language": "auto",
  "conversation_id": "a1b2c3d4-...",
  "task_id": "t1a2b3c4-...",
  "enable_diarization": true,
  "hf_token": null
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `audio_object_key` | String | 是 | MinIO 中的音频文件路径 |
| `bucket` | String | 否 | Bucket 名称，默认 `raw-audio` |
| `language` | String | 否 | 语言：`auto` / `zh` / `en` |
| `conversation_id` | String | 是 | 关联的对话 ID |
| `task_id` | String | 是 | 任务 ID |
| `enable_diarization` | bool | 否 | 是否启用说话人分离，默认 true |
| `hf_token` | String | 否 | HuggingFace Token（说话人分离需要） |

**响应示例**

```json
{
  "status": "success",
  "task_id": "t1a2b3c4-...",
  "conversation_id": "a1b2c3d4-...",
  "transcript": "完整转写文本...",
  "segments": [
    {
      "start": 0.0,
      "end": 3.5,
      "text": "你好，今天天气不错。",
      "speaker": "SPEAKER_00",
      "words": [
        { "word": "你好", "start": 0.0, "end": 0.5, "score": 0.98 }
      ]
    }
  ],
  "speakers": [
    {
      "speaker_id": "SPEAKER_00",
      "total_speaking_time": 120.5,
      "segment_count": 15
    }
  ],
  "language": "zh",
  "duration_seconds": 180.0,
  "processed_at": "2024-01-15T10:35:00"
}
```

---

### 文本摘要 — `/api/v1/summarize`

#### POST `/api/v1/summarize`

使用本地 Qwen2.5 模型生成对话摘要。

**请求体**

```json
{
  "conversation_id": "a1b2c3d4-...",
  "text": "待摘要的转写文本...",
  "context": "额外上下文信息（可选）",
  "task_id": "t1a2b3c4-..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `conversation_id` | String | 是 | 对话 ID |
| `text` | String | 是 | 待摘要文本 |
| `context` | String | 否 | 额外上下文 |
| `task_id` | String | 否 | 任务 ID |

**响应示例**

```json
{
  "status": "success",
  "conversation_id": "a1b2c3d4-...",
  "summary": "这是一段关于项目进度的讨论...",
  "key_persons": ["张三", "李四"],
  "key_topics": ["项目进度", "技术方案"],
  "sentiment": "positive",
  "sentiment_score": 0.72,
  "processed_at": "2024-01-15T10:35:30"
}
```

---

### 晨间胶囊 — `/api/v1/morning-capsule`

#### POST `/api/v1/morning-capsule`

汇总前一天对话摘要，生成每日记忆精华。

**请求体**

```json
{
  "date": "2024-01-15",
  "conversation_ids": ["id-1", "id-2"],
  "summaries": [
    "上午和张三讨论了项目进度...",
    "下午和李四一起看了技术方案..."
  ]
}
```

**响应示例**

```json
{
  "status": "success",
  "date": "2024-01-15",
  "capsule": "今天是充实的一天，上午和张三讨论了项目进展...",
  "highlights": ["项目进度达成共识", "技术方案选型确定"],
  "people_mentioned": ["张三", "李四"],
  "mood_summary": "positive",
  "processed_at": "2024-01-16T07:00:00"
}
```

---

### 记忆卡片 — `/api/v1/generate-card`

#### POST `/api/v1/generate-card`

根据情感峰值片段生成视觉记忆卡片。

**请求体**

```json
{
  "conversation_id": "a1b2c3d4-...",
  "summary": "对话摘要文本...",
  "peak_segment": "情感峰值片段文本...",
  "sentiment_score": 0.85,
  "style": "ambient",
  "style_hint": "温暖"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `conversation_id` | String | 是 | 对话 ID |
| `summary` | String | 是 | 对话摘要 |
| `peak_segment` | String | 否 | 情感峰值片段 |
| `sentiment_score` | float | 否 | 情感分数 -1.0~1.0 |
| `style` | String | 否 | 卡片风格：`artistic` / `ambient` / `abstract`，默认 `ambient` |
| `style_hint` | String | 否 | 风格提示词 |

**卡片风格说明**

| 风格 | 说明 |
|------|------|
| `artistic` | 艺术评释 — 水彩画风格 |
| `ambient` | 氛围场景 — 自然光影 |
| `abstract` | 抄象联结 — 彩色图案 |

**响应示例**

```json
{
  "status": "success",
  "conversation_id": "a1b2c3d4-...",
  "card_object_key": "card/a1b2c3d4/ambient_xxx.svg",
  "card_url": "http://localhost:9000/memory-cards/card/...?X-Amz-Algorithm=...",
  "sd_prompt": "watercolor painting of a warm conversation, soft light...",
  "processed_at": "2024-01-15T10:36:00"
}
```

> 预签名 URL 有效期 7 天。图像生成优先使用本地 ComfyUI API，不可用时降级为 SVG 矢量卡片。

---

### 向量嵌入 — `/api/v1/embed`

#### POST `/api/v1/embed`

使用本地 Sentence Transformers 模型对文本进行向量化。

**请求体**

```json
{
  "texts": ["这段文本需要向量化", "另一段文本"],
  "model": null
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `texts` | String[] | 是 | 待嵌入文本列表 |
| `model` | String | 否 | 指定模型（默认使用 BAAI/bge-small-zh-v1.5） |

**响应示例**

```json
{
  "status": "success",
  "embeddings": [
    [0.012, -0.034, 0.056, ...],
    [0.078, 0.091, -0.023, ...]
  ],
  "model": "BAAI/bge-small-zh-v1.5",
  "dimension": 512
}
```

---

### 健康检查 — `/health`

#### GET `/health`

数据面健康检查端点。

**响应示例**

```json
{
  "status": "healthy",
  "service": "echo-data-plane",
  "version": "0.1.0",
  "consumer_running": true
}
```

---

## 错误码说明

### 控制面错误码

| 错误码 | HTTP 状态码 | 说明 |
|--------|-----------|------|
| 200 | 200 | 成功 |
| 400 | 400 | 参数校验失败 |
| 1001 | 400 | 音频文件为空 |
| 1002 | 400 | 音频格式不支持 |
| 1003 | 500 | 音频上传至存储失败 |
| 1004 | 500 | 音频加密失败 |
| 1005 | 500 | 预签名 URL 生成失败 |
| 2001 | 500 | 任务入队失败 |
| 2002 | 404 | 任务不存在 |
| 2003 | 500 | 任务状态查询失败 |
| 3001 | 404 | 人物节点不存在 |
| 3002 | 500 | 图谱查询失败 |
| 3003 | 500 | 图谱更新失败 |
| 3004 | 404 | 对话节点不存在 |
| 4001 | 503 | P2P 同步未启用 |
| 4002 | 500 | P2P 同步失败 |
| 4003 | 401 | P2P 签名验证失败 |
| 4004 | 400 | P2P 快照解密失败 |
| 5001 | 400 | 回调数据格式错误 |
| 5002 | 500 | 回调处理失败 |
| 6001 | 401 | API Key 无效 |
| 6002 | 401 | Token 无效或已过期 |
| 7001 | 503 | 数据面服务不可用 |
| 7002 | 500 | 向量嵌入调用失败 |
| 8001 | 500 | 语义搜索失败 |

### 数据面错误码

数据面使用标准 HTTP 状态码：

| HTTP 状态码 | 说明 |
|------------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 404 | 资源未找到（如音频文件不存在） |
| 422 | 请求体验证失败（Pydantic 校验） |
| 500 | 服务内部错误（AI 推理失败等） |
