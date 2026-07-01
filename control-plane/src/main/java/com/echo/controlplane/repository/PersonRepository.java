package com.echo.controlplane.repository;

import com.echo.controlplane.model.PersonNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 人物节点 Neo4j 数据访问接口
 *
 * <p>提供对 Person 节点的 CRUD 操作和自定义 Cypher 查询。
 * Spring Data Neo4j 会自动生成基础实现。
 */
@Repository
public interface PersonRepository extends Neo4jRepository<PersonNode, Long> {

    /**
     * 根据姓名查找人物节点
     *
     * @param name 人物姓名
     * @return 匹配的人物节点（可能为空）
     */
    Optional<PersonNode> findByName(String name);

    /**
     * 查找提及次数超过阈值的重要人物
     *
     * @param minCount 最小提及次数
     * @return 满足条件的人物列表，按提及次数降序排列
     */
    @Query("MATCH (p:Person) WHERE p.mentionCount >= $minCount RETURN p ORDER BY p.mentionCount DESC")
    List<PersonNode> findFrequentPersons(int minCount);

    /**
     * 查找与指定人物有 KNOWS 关系的人物
     *
     * @param name 中心人物姓名
     * @return 认识该人物的其他人列表
     */
    @Query("MATCH (p:Person {name: $name})-[:KNOWS]-(other:Person) RETURN other")
    List<PersonNode> findConnectedPersons(String name);

    /**
     * 搜索人物（模糊匹配姓名）
     *
     * @param keyword 搜索关键词
     * @return 匹配的人物列表
     */
    @Query("MATCH (p:Person) WHERE p.name CONTAINS $keyword RETURN p")
    List<PersonNode> searchByName(String keyword);

    /**
     * 查找与指定人物有 INTERACTED_WITH 关系的人物（双向）
     *
     * @param name 中心人物姓名
     * @return 与该人物有互动关系的其他人列表
     */
    @Query("MATCH (p:Person {name: $name})-[:INTERACTED_WITH]-(other:Person) RETURN other " +
           "ORDER BY other.interactionCount DESC")
    List<PersonNode> findInteractedPersons(String name);

    /**
     * 按声纹哈希查找人物（声纹识别用）
     *
     * @param voiceHash SHA-256 声纹哈希
     * @return 匹配的人物节点
     */
    Optional<PersonNode> findByVoiceHash(String voiceHash);

    /**
     * 获取互动次数 Top N 的人物（用于重要联系人排序）
     *
     * @param limit 返回数量上限
     * @return 互动最频繁的人物列表
     */
    @Query("MATCH (p:Person) WHERE p.interactionCount > 0 " +
           "RETURN p ORDER BY p.interactionCount DESC LIMIT $limit")
    List<PersonNode> findTopByInteractionCount(int limit);
}
