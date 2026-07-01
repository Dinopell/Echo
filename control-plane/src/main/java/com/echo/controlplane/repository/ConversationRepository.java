package com.echo.controlplane.repository;

import com.echo.controlplane.model.ConversationNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 对话节点 Neo4j 数据访问接口
 *
 * <p>提供对 Conversation 节点的 CRUD 操作和自定义 Cypher 查询。
 */
@Repository
public interface ConversationRepository extends Neo4jRepository<ConversationNode, Long> {

    /**
     * 根据对话 ID 查找节点
     *
     * @param conversationId 对话唯一标识
     * @return 对话节点（可能为空）
     */
    Optional<ConversationNode> findByConversationId(String conversationId);

    /**
     * 根据任务 ID 查找对话节点（用于处理回调）
     *
     * @param taskId Redis 任务 ID
     * @return 对话节点（可能为空）
     */
    Optional<ConversationNode> findByTaskId(String taskId);

    /**
     * 按时间范围查询对话列表
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 时间范围内的对话列表
     */
    @Query("MATCH (c:Conversation) WHERE c.recordedAt >= $start AND c.recordedAt <= $end RETURN c ORDER BY c.recordedAt DESC")
    List<ConversationNode> findByTimeRange(LocalDateTime start, LocalDateTime end);

    /**
     * 查找处理中（pending 或 processing）的对话
     *
     * @return 未完成处理的对话列表
     */
    @Query("MATCH (c:Conversation) WHERE c.status IN ['pending', 'processing'] RETURN c")
    List<ConversationNode> findPendingConversations();

    /**
     * 统计最近 N 天的对话数量
     *
     * @param since 起始时间
     * @return 对话数量
     */
    @Query("MATCH (c:Conversation) WHERE c.recordedAt >= $since RETURN count(c)")
    Long countConversationsSince(LocalDateTime since);

    /**
     * 按人名查询对话（查找 Person 节点参与的对话）
     *
     * @param personName 人物姓名（支持模糊匹配）
     * @return 包含该人物的对话列表
     */
    @Query("MATCH (p:Person)-[:MENTIONED_IN]->(c:Conversation) " +
           "WHERE p.name CONTAINS $personName " +
           "RETURN c ORDER BY c.recordedAt DESC")
    List<ConversationNode> findByPersonName(String personName);

    /**
     * 按话题关键词搜索对话（匹配摘要或转写文本）
     *
     * @param keyword 关键词
     * @return 相关对话列表
     */
    @Query("MATCH (c:Conversation) " +
           "WHERE (c.summary CONTAINS $keyword OR c.transcript CONTAINS $keyword) " +
           "RETURN c ORDER BY c.recordedAt DESC")
    List<ConversationNode> findByTopicKeyword(String keyword);
}
