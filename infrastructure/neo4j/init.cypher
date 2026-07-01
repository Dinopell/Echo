// ============================================================
// Project Echo — Neo4j 初始化 Cypher 脚本
//
// 创建必要的约束、索引和向量索引。
// 在 Neo4j 容器首次启动后手动执行：
//   docker exec -it echo-neo4j cypher-shell -u neo4j -p <password> -f /docker-entrypoint-initdb.d/init.cypher
// ============================================================

// ──────────────────────────────────────────────
// 1. 唯一性约束
// ──────────────────────────────────────────────

// Person 节点：name 属性唯一（同名人物共享一个节点）
CREATE CONSTRAINT person_name_unique IF NOT EXISTS
FOR (p:Person) REQUIRE p.name IS UNIQUE;

// Conversation 节点：conversationId 唯一
CREATE CONSTRAINT conversation_id_unique IF NOT EXISTS
FOR (c:Conversation) REQUIRE c.conversationId IS UNIQUE;

// ──────────────────────────────────────────────
// 2. 普通索引（加速查询）
// ──────────────────────────────────────────────

// Person 节点：按提及次数倒排（查询重要人物）
CREATE INDEX person_mention_count IF NOT EXISTS
FOR (p:Person) ON (p.mentionCount);

// Person 节点：按最后见面时间索引
CREATE INDEX person_last_seen IF NOT EXISTS
FOR (p:Person) ON (p.lastSeen);

// Conversation 节点：按录制时间索引（按时间查询对话）
CREATE INDEX conversation_recorded_at IF NOT EXISTS
FOR (c:Conversation) ON (c.recordedAt);

// Conversation 节点：按处理状态索引（查询待处理任务）
CREATE INDEX conversation_status IF NOT EXISTS
FOR (c:Conversation) ON (c.status);

// Conversation 节点：按任务 ID 索引（处理结果回调时查找对应节点）
CREATE INDEX conversation_task_id IF NOT EXISTS
FOR (c:Conversation) ON (c.taskId);

// ──────────────────────────────────────────────
// 3. 全文搜索索引
// ──────────────────────────────────────────────

// Conversation 全文索引：支持对转写文本和摘要进行关键词搜索
CREATE FULLTEXT INDEX conversation_text_search IF NOT EXISTS
FOR (c:Conversation) ON EACH [c.transcript, c.summary];

// ──────────────────────────────────────────────
// 4. 向量索引（Neo4j 5.x 向量搜索）
// 用于语义相似度搜索（记忆召回）
// ──────────────────────────────────────────────

// Conversation 向量索引（512 维，BAAI/bge-small-zh-v1.5 嵌入）
// 注意：向量字段 embeddingVector 由 data-plane 在处理完成后写入
CREATE VECTOR INDEX conversation_embedding IF NOT EXISTS
FOR (c:Conversation) ON (c.embeddingVector)
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 512,
    `vector.similarity_function`: 'cosine'
  }
};

// ──────────────────────────────────────────────
// 5. 验证创建结果
// ──────────────────────────────────────────────

// 查看所有约束
SHOW CONSTRAINTS;

// 查看所有索引
SHOW INDEXES;
