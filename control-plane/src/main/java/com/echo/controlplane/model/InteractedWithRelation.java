package com.echo.controlplane.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j 人物互动关系属性实体
 *
 * <p>表示两个人物节点之间的 INTERACTED_WITH 关系，
 * 包含互动频率、平均情感分数、涉及话题等元信息。
 *
 * <p>图结构示例：
 * <pre>
 *   (Alice:Person)-[:INTERACTED_WITH {frequency: 5, avgSentiment: 0.8}]->(Bob:Person)
 * </pre>
 */
@RelationshipProperties
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractedWithRelation {

    /** Neo4j 内部关系 ID */
    @RelationshipId
    private Long id;

    /** 关系目标节点（另一端人物） */
    @TargetNode
    private PersonNode target;

    /** 互动总频次（每次对话中同时出现则 +1） */
    @Builder.Default
    private Integer frequency = 1;

    /** 平均情感分数（-1.0 ~ 1.0，正值为积极） */
    @Builder.Default
    private Double avgSentiment = 0.0;

    /** 互动涉及的主要话题列表 */
    @Builder.Default
    private List<String> topics = new ArrayList<>();

    /** 最后一次互动时间 */
    private LocalDateTime lastInteraction;
}
