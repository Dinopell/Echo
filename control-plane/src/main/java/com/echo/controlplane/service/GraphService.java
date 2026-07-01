package com.echo.controlplane.service;

import com.echo.controlplane.dto.SemanticSearchResult;
import com.echo.controlplane.dto.TranscribeCallbackRequest;
import com.echo.controlplane.exception.EchoException;
import com.echo.controlplane.model.ConversationNode;
import com.echo.controlplane.model.InteractedWithRelation;
import com.echo.controlplane.model.PersonNode;
import com.echo.controlplane.repository.ConversationRepository;
import com.echo.controlplane.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.types.Node;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识图谱管理服务
 *
 * <p>负责维护 Neo4j 中的人物和对话节点及其关系。
 * 控制面通过此服务读写图谱，数据面的 AI 处理结果会回调此服务更新节点。
 *
 * <p>节点和关系类型：
 * <pre>
 *   (Person)-[:MENTIONED_IN]->(Conversation)
 *   (Person)-[:INTERACTED_WITH {frequency, avgSentiment, topics}]->(Person)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "transactionManager")
public class GraphService {

    private static final String VECTOR_INDEX_NAME = "conversation_embedding";

    private final PersonRepository personRepository;
    private final ConversationRepository conversationRepository;
    private final DataPlaneClient dataPlaneClient;
    private final Neo4jClient neo4jClient;
    private final Neo4jTemplate neo4jTemplate;

    // ── 人物节点操作 ──────────────────────────────────────────

    /**
     * 创建或更新人物节点
     * 如果同名人物已存在，则更新提及次数和最后见面时间
     *
     * @param name         人物姓名
     * @param relationship 与用户的关系
     * @return 保存后的人物节点
     */
    public PersonNode upsertPerson(String name, String relationship) {
        Optional<PersonNode> existing = personRepository.findByName(name);
        if (existing.isPresent()) {
            PersonNode person = existing.get();
            person.setLastSeen(LocalDateTime.now());
            person.setMentionCount(person.getMentionCount() + 1);
            if (relationship != null) {
                person.setRelationship(relationship);
            }
            log.debug("更新人物节点: name={}, mentionCount={}", name, person.getMentionCount());
            return personRepository.save(person);
        } else {
            PersonNode newPerson = PersonNode.builder()
                    .name(name)
                    .relationship(relationship)
                    .firstSeen(LocalDateTime.now())
                    .lastSeen(LocalDateTime.now())
                    .mentionCount(1)
                    .interactionCount(0)
                    .build();
            log.info("创建新人物节点: name={}", name);
            return personRepository.save(newPerson);
        }
    }

    /**
     * 获取所有人物节点列表（按互动次数排序）
     *
     * @return 所有人物节点
     */
    public List<PersonNode> getPersons() {
        return personRepository.findAll().stream()
                .sorted((a, b) -> {
                    int countA = a.getInteractionCount() != null ? a.getInteractionCount() : 0;
                    int countB = b.getInteractionCount() != null ? b.getInteractionCount() : 0;
                    return Integer.compare(countB, countA);
                })
                .toList();
    }

    /**
     * 查询提及频率最高的重要人物
     *
     * @param limit 返回数量上限
     * @return 人物列表
     */
    public List<PersonNode> getImportantPersons(int limit) {
        // 提及次数 >= 3 视为重要人物
        return personRepository.findFrequentPersons(3)
                .stream()
                .limit(limit)
                .toList();
    }

    /**
     * 获取某人物的社交关系网络
     *
     * @param personName 人物姓名
     * @return 相关联人物列表
     */
    public List<PersonNode> getPersonNetwork(String personName) {
        return personRepository.findConnectedPersons(personName);
    }

    /**
     * 按人名查询其参与的所有对话（含关联对话节点）
     *
     * @param personName 人物姓名（模糊匹配）
     * @return 该人物参与的对话列表
     */
    public List<ConversationNode> queryByPerson(String personName) {
        if (personName == null || personName.isBlank()) {
            throw new EchoException("人名不能为空", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        try {
            List<ConversationNode> conversations = conversationRepository.findByPersonName(personName);
            log.debug("按人名查询对话: personName={}, found={}", personName, conversations.size());
            return conversations;
        } catch (Exception e) {
            log.error("按人名查询图谱失败: personName={}", personName, e);
            throw new EchoException(EchoException.ErrorCode.GRAPH_QUERY_FAILED, personName);
        }
    }

    /**
     * 按时间范围查询对话列表
     *
     * @param start 起始时间
     * @param end   截止时间
     * @return 时间范围内的对话列表（按时间倒序）
     */
    public List<ConversationNode> queryByTimeRange(LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            start = LocalDateTime.now().minusDays(7);
        }
        if (end == null) {
            end = LocalDateTime.now();
        }
        if (start.isAfter(end)) {
            throw new EchoException("startTime 不能晚于 endTime",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        try {
            List<ConversationNode> conversations = conversationRepository.findByTimeRange(start, end);
            log.debug("按时间范围查询对话: start={}, end={}, found={}", start, end, conversations.size());
            return conversations;
        } catch (Exception e) {
            log.error("按时间范围查询图谱失败", e);
            throw new EchoException(EchoException.ErrorCode.GRAPH_QUERY_FAILED, "时间范围查询异常");
        }
    }

    // ── 图谱更新 ──────────────────────────────────────────────

    /**
     * 根据数据面完整回调更新对话节点
     */
    public void updateConversationFromCallback(TranscribeCallbackRequest request) {
        String conversationId = request.getConversationId();
        conversationRepository.findByConversationId(conversationId).ifPresent(conv -> {
            if (request.getTranscript() != null) {
                conv.setTranscript(request.getTranscript());
            }
            if (request.getSummary() != null) {
                conv.setSummary(request.getSummary());
            }
            if (request.getSentiment() != null) {
                conv.setSentiment(request.getSentiment());
            }
            if (request.getCardObjectKey() != null) {
                conv.setCardObjectKey(request.getCardObjectKey());
            }
            if (request.getEmbedding() != null && !request.getEmbedding().isEmpty()) {
                conv.setEmbeddingVector(toFloatArray(request.getEmbedding()));
            }
            conv.setStatus(request.getStatus() != null ? request.getStatus() : "done");
            conversationRepository.save(conv);
            log.info("对话节点已更新: conversationId={}, status={}", conversationId, conv.getStatus());
        });
    }

    /**
     * 写入对话节点的语义向量
     */
    public void updateConversationEmbedding(String conversationId, List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return;
        }
        conversationRepository.findByConversationId(conversationId).ifPresent(conv -> {
            conv.setEmbeddingVector(toFloatArray(embedding));
            conversationRepository.save(conv);
            log.info("对话向量已更新: conversationId={}, dimension={}",
                    conversationId, embedding.size());
        });
    }

    /**
     * 更新对话节点的转写结果和处理状态
     *
     * @param conversationId 对话 ID
     * @param transcript     转写文本（可为 null，不更新）
     * @param summary        AI 摘要（可为 null，不更新）
     * @param status         处理状态
     */
    public void updateConversationResult(String conversationId, String transcript,
                                          String summary, String status) {
        conversationRepository.findByConversationId(conversationId).ifPresent(conv -> {
            if (transcript != null) {
                conv.setTranscript(transcript);
            }
            if (summary != null) {
                conv.setSummary(summary);
            }
            conv.setStatus(status);
            conversationRepository.save(conv);
            log.info("对话节点已更新: conversationId={}, status={}", conversationId, status);
        });
    }

    /**
     * 根据转写结果更新图谱（人物节点 + INTERACTED_WITH 关系）
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>对每位参与者，调用 upsertPerson() 更新/创建节点，interactionCount +1</li>
     *   <li>对每对参与者，更新 INTERACTED_WITH 关系（frequency +1，avgSentiment 滚动平均）</li>
     * </ol>
     *
     * @param conversationId 对话 ID
     * @param participants   参与人物姓名数组
     * @param sentimentScore 情感分数（-1.0 ~ 1.0）
     * @param topics         对话摘要（用于话题提取）
     */
    public void updateGraph(String conversationId, String[] participants,
                             double sentimentScore, String topics) {
        if (participants == null || participants.length == 0) {
            log.debug("无参与者信息，跳过图谱互动关系更新: conversationId={}", conversationId);
            return;
        }

        log.info("更新图谱互动关系: conversationId={}, participants={}, sentiment={}",
                conversationId, Arrays.toString(participants), sentimentScore);

        // 1. 更新每位参与者的节点
        PersonNode[] personNodes = new PersonNode[participants.length];
        for (int i = 0; i < participants.length; i++) {
            String name = participants[i].trim();
            if (name.isBlank()) continue;

            PersonNode person = personRepository.findByName(name).orElseGet(() ->
                    PersonNode.builder()
                            .name(name)
                            .firstSeen(LocalDateTime.now())
                            .lastSeen(LocalDateTime.now())
                            .mentionCount(1)
                            .interactionCount(0)
                            .build());

            // interactionCount +1
            person.setInteractionCount(
                    person.getInteractionCount() != null ? person.getInteractionCount() + 1 : 1);
            person.setLastSeen(LocalDateTime.now());
            personNodes[i] = personRepository.save(person);
        }

        // 2. 为每对参与者创建/更新 INTERACTED_WITH 关系
        for (int i = 0; i < personNodes.length; i++) {
            for (int j = i + 1; j < personNodes.length; j++) {
                PersonNode nodeA = personNodes[i];
                PersonNode nodeB = personNodes[j];
                if (nodeA == null || nodeB == null) continue;

                updateInteractionRelation(nodeA, nodeB, sentimentScore, topics);
            }
        }
    }

    /**
     * 获取近期对话列表
     *
     * @param days 最近几天
     * @return 对话节点列表
     */
    public List<ConversationNode> getRecentConversations(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return conversationRepository.findByTimeRange(since, LocalDateTime.now());
    }

    /**
     * 语义向量搜索：调用数据面生成查询向量，再通过 Neo4j Vector Index 召回相似对话
     *
     * @param query    自然语言查询
     * @param limit    返回数量上限
     * @param minScore 最低余弦相似度（0~1）
     * @return 按相似度降序排列的对话列表
     */
    public List<SemanticSearchResult> semanticSearch(String query, int limit, double minScore) {
        if (query == null || query.isBlank()) {
            throw new EchoException("query 不能为空", org.springframework.http.HttpStatus.BAD_REQUEST);
        }

        try {
            List<Double> embedding = dataPlaneClient.embedText(query.trim());
            float[] vector = toFloatArray(embedding);

            String cypher = """
                    CALL db.index.vector.queryNodes($indexName, $limit, $embedding)
                    YIELD node, score
                    WHERE score >= $minScore
                    RETURN node, score
                    ORDER BY score DESC
                    """;

            Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                    .bind(VECTOR_INDEX_NAME).to("indexName")
                    .bind(limit).to("limit")
                    .bind(vector).to("embedding")
                    .bind(minScore).to("minScore")
                    .fetch()
                    .all();

            List<SemanticSearchResult> results = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object nodeObj = row.get("node");
                Object scoreObj = row.get("score");
                if (!(nodeObj instanceof Node driverNode) || scoreObj == null) {
                    continue;
                }
                double score = ((Number) scoreObj).doubleValue();
                neo4jTemplate.findById(driverNode.id(), ConversationNode.class)
                        .ifPresent(conv -> results.add(
                                SemanticSearchResult.builder()
                                        .conversation(conv)
                                        .score(score)
                                        .build()));
            }

            log.info("语义搜索完成: queryLength={}, hits={}", query.length(), results.size());
            return results;

        } catch (EchoException e) {
            throw e;
        } catch (Exception e) {
            log.error("语义搜索失败: {}", e.getMessage(), e);
            throw new EchoException(EchoException.ErrorCode.SEMANTIC_SEARCH_FAILED, e.getMessage());
        }
    }

    // ── 私有辅助方法 ──────────────────────────────────────────

    private float[] toFloatArray(List<Double> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }

    /**
     * 更新两个人物之间的 INTERACTED_WITH 关系
     *
     * <p>若关系不存在则创建，存在则更新 frequency 和 avgSentiment（滚动平均）。
     *
     * @param nodeA          人物 A
     * @param nodeB          人物 B
     * @param sentimentScore 本次情感分数
     * @param topicHint      话题提示（来自摘要，用于话题列表）
     */
    private void updateInteractionRelation(PersonNode nodeA, PersonNode nodeB,
                                            double sentimentScore, String topicHint) {
        // 在 nodeA 的 interactions 集合中查找指向 nodeB 的关系
        Optional<InteractedWithRelation> existingRelOpt = nodeA.getInteractions().stream()
                .filter(r -> r.getTarget() != null
                        && r.getTarget().getId() != null
                        && r.getTarget().getId().equals(nodeB.getId()))
                .findFirst();

        if (existingRelOpt.isPresent()) {
            // 更新已有关系
            InteractedWithRelation rel = existingRelOpt.get();
            int newFrequency = rel.getFrequency() + 1;
            // 滚动平均情感分数
            double newAvgSentiment = (rel.getAvgSentiment() * rel.getFrequency() + sentimentScore) / newFrequency;
            rel.setFrequency(newFrequency);
            rel.setAvgSentiment(newAvgSentiment);
            rel.setLastInteraction(LocalDateTime.now());
            // 添加话题（避免重复）
            if (topicHint != null && !topicHint.isBlank()) {
                String shortTopic = topicHint.length() > 20 ? topicHint.substring(0, 20) : topicHint;
                if (!rel.getTopics().contains(shortTopic)) {
                    rel.getTopics().add(shortTopic);
                }
            }
        } else {
            // 创建新关系
            InteractedWithRelation newRel = InteractedWithRelation.builder()
                    .target(nodeB)
                    .frequency(1)
                    .avgSentiment(sentimentScore)
                    .lastInteraction(LocalDateTime.now())
                    .build();
            if (topicHint != null && !topicHint.isBlank()) {
                String shortTopic = topicHint.length() > 20 ? topicHint.substring(0, 20) : topicHint;
                newRel.getTopics().add(shortTopic);
            }
            nodeA.getInteractions().add(newRel);
        }

        personRepository.save(nodeA);
        log.debug("互动关系已更新: {} <-> {}", nodeA.getName(), nodeB.getName());
    }
}
