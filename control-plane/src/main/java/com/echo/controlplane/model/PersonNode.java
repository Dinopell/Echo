package com.echo.controlplane.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j 人物节点实体
 *
 * <p>表示用户生活中的一位联系人（家人、朋友、同事等）。
 * 人物节点是 Echo 记忆图谱的核心，通过关系连接到对话节点。
 *
 * <p>图结构示例：
 * <pre>
 *   (Alice:Person)-[:MENTIONED_IN]->(conv123:Conversation)
 *   (Alice:Person)-[:INTERACTED_WITH {frequency: 5, avgSentiment: 0.8}]->(Bob:Person)
 * </pre>
 */
@Node("Person")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonNode {

    /** Neo4j 内部 ID */
    @Id
    @GeneratedValue
    private Long id;

    /** 人物姓名（唯一标识符） */
    @Property("name")
    private String name;

    /** 与用户的关系描述（如：同事、家人、朋友） */
    @Property("relationship")
    private String relationship;

    /** 人物摘要描述（由 AI 自动生成） */
    @Property("summary")
    private String summary;

    /** 声纹特征哈希（SHA-256，用于声纹识别辅助） */
    @Property("voiceHash")
    private String voiceHash;

    /** 首次出现时间 */
    @Property("firstSeen")
    private LocalDateTime firstSeen;

    /** 最后提及时间 */
    @Property("lastSeen")
    private LocalDateTime lastSeen;

    /** 出现频次（越高越重要） */
    @Property("mentionCount")
    @Builder.Default
    private Integer mentionCount = 0;

    /** 互动总次数（跨对话累计） */
    @Property("interactionCount")
    @Builder.Default
    private Integer interactionCount = 0;

    /** 该人物出现在哪些对话中 */
    @Relationship(type = "MENTIONED_IN", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ConversationNode> conversations = new HashSet<>();

    /** 与其他人物的互动关系（INTERACTED_WITH） */
    @Relationship(type = "INTERACTED_WITH", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<InteractedWithRelation> interactions = new HashSet<>();
}
