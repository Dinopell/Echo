package com.echo.controlplane.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.time.LocalDateTime;

/**
 * Neo4j 对话节点实体
 *
 * <p>每次用户录音对话对应一个 ConversationNode。
 * 包含转写文本、AI 摘要、情感标签、时长等元数据。
 *
 * <p>音频原文件存储在 MinIO 的 raw-audio bucket，
 * 此节点只保存元数据和摘要，不存储音频二进制内容。
 */
@Node("Conversation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationNode {

    /** Neo4j 内部 ID */
    @Id
    @GeneratedValue
    private Long id;

    /** 对话唯一标识（与 MinIO 文件名对应） */
    @Property("conversationId")
    private String conversationId;

    /** MinIO 中音频文件的对象键 */
    @Property("audioObjectKey")
    private String audioObjectKey;

    /** 转写文本（由 WhisperX 生成） */
    @Property("transcript")
    private String transcript;

    /** AI 生成的摘要（由 Qwen2.5 生成） */
    @Property("summary")
    private String summary;

    /** 情感标签（positive / negative / neutral） */
    @Property("sentiment")
    private String sentiment;

    /** 对话时长（秒） */
    @Property("durationSeconds")
    private Integer durationSeconds;

    /** 对话发生时间 */
    @Property("recordedAt")
    private LocalDateTime recordedAt;

    /** 任务处理状态（pending / processing / done / failed） */
    @Property("status")
    @Builder.Default
    private String status = "pending";

    /** 任务 ID（用于在 Redis 中查询处理进度） */
    @Property("taskId")
    private String taskId;

    /** 512 维语义向量（BGE-Small 嵌入） */
    @Property("embeddingVector")
    private float[] embeddingVector;

    /** 记忆卡片在 MinIO 中的对象键 */
    @Property("cardObjectKey")
    private String cardObjectKey;
}
