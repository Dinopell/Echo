package com.echo.controlplane.controller;

import com.echo.controlplane.dto.ApiResponse;
import com.echo.controlplane.dto.SemanticSearchRequest;
import com.echo.controlplane.dto.SemanticSearchResult;
import com.echo.controlplane.model.ConversationNode;
import com.echo.controlplane.model.PersonNode;
import com.echo.controlplane.service.GraphService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱控制器
 *
 * <p>API 端点：
 * <pre>
 *   GET    /api/v1/graph/persons                  — 获取人物列表（全部或按频率过滤）
 *   GET    /api/v1/graph/persons/{name}/network   — 获取人物社交网络
 *   POST   /api/v1/graph/persons                  — 手动创建/更新人物节点
 *   GET    /api/v1/graph/query                    — 多条件查询图谱（人名/时间/话题）
 *   GET    /api/v1/graph/conversations            — 获取近期对话
 *   POST   /api/v1/graph/search/semantic          — 语义向量搜索
 *   GET    /api/v1/graph/search/semantic          — 语义向量搜索（GET 便捷方式）
 *   PUT    /api/v1/graph/conversations/{id}       — 更新对话处理结果
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/graph")
@RequiredArgsConstructor
public class GraphController {

    private final GraphService graphService;

    /**
     * 获取所有人物节点列表（按互动次数降序）
     *
     * @return 所有人物节点
     */
    @GetMapping("/persons")
    public ResponseEntity<ApiResponse<List<PersonNode>>> getPersons() {
        List<PersonNode> persons = graphService.getPersons();
        return ResponseEntity.ok(ApiResponse.success(persons));
    }

    /**
     * 获取重要人物列表（按提及频率排序）
     *
     * @param limit 返回数量限制，默认 20
     * @return 人物节点列表
     */
    @GetMapping("/persons/important")
    public ResponseEntity<ApiResponse<List<PersonNode>>> getImportantPersons(
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("limit 范围为 1~100"));
        }
        return ResponseEntity.ok(ApiResponse.success(graphService.getImportantPersons(limit)));
    }

    /**
     * 获取指定人物的社交关系网络
     *
     * @param name 人物姓名
     * @return 与该人物有关联的其他人物列表
     */
    @GetMapping("/persons/{name}/network")
    public ResponseEntity<ApiResponse<List<PersonNode>>> getPersonNetwork(
            @PathVariable String name) {
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("人物姓名不能为空"));
        }
        return ResponseEntity.ok(ApiResponse.success(graphService.getPersonNetwork(name)));
    }

    /**
     * 手动创建或更新人物节点
     *
     * @param request 包含 name 和 relationship 的请求体
     * @return 保存后的人物节点
     */
    @PostMapping("/persons")
    public ResponseEntity<ApiResponse<PersonNode>> upsertPerson(
            @RequestBody Map<String, String> request) {
        String name = request.get("name");
        String relationship = request.get("relationship");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("name 不能为空"));
        }
        PersonNode saved = graphService.upsertPerson(name, relationship);
        return ResponseEntity.ok(ApiResponse.success("人物节点已保存", saved));
    }

    /**
     * 多条件图谱查询接口
     *
     * <p>支持以下查询维度（可组合）：
     * <ul>
     *   <li>personName — 按人名模糊查询</li>
     *   <li>startTime/endTime — 按时间范围查询</li>
     *   <li>topic — 按话题关键词搜索</li>
     * </ul>
     *
     * @param personName  人物姓名（可选）
     * @param startTime   起始时间 ISO-8601（可选）
     * @param endTime     截止时间 ISO-8601（可选）
     * @param topic       话题关键词（可选）
     * @param limit       返回数量上限（默认 20）
     * @return 查询到的对话节点列表
     */
    @GetMapping("/query")
    public ResponseEntity<ApiResponse<List<ConversationNode>>> queryGraph(
            @RequestParam(required = false) String personName,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "20") int limit) {

        // 按人名查询（优先）
        if (personName != null && !personName.isBlank()) {
            log.debug("图谱查询（按人名）: personName={}", personName);
            List<ConversationNode> result = graphService.queryByPerson(personName);
            return ResponseEntity.ok(ApiResponse.success(
                    result.stream().limit(limit).toList()));
        }

        // 按话题查询
        if (topic != null && !topic.isBlank()) {
            log.debug("图谱查询（按话题）: topic={}", topic);
            // 复用 ConversationRepository 的 findByTopicKeyword
            List<ConversationNode> result = graphService.getRecentConversations(365).stream()
                    .filter(c -> (c.getSummary() != null && c.getSummary().contains(topic))
                            || (c.getTranscript() != null && c.getTranscript().contains(topic)))
                    .limit(limit)
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(result));
        }

        // 按时间范围查询（默认最近 7 天）
        LocalDateTime start = parseDateTime(startTime, LocalDateTime.now().minusDays(7));
        LocalDateTime end = parseDateTime(endTime, LocalDateTime.now());
        log.debug("图谱查询（按时间范围）: start={}, end={}", start, end);
        List<ConversationNode> result = graphService.queryByTimeRange(start, end);
        return ResponseEntity.ok(ApiResponse.success(
                result.stream().limit(limit).toList()));
    }

    /**
     * 获取近期对话列表
     *
     * @param days 查询最近几天的对话，默认 7 天
     * @return 对话节点列表
     */
    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<ConversationNode>>> getRecentConversations(
            @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 365) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("days 范围为 1~365"));
        }
        return ResponseEntity.ok(ApiResponse.success(graphService.getRecentConversations(days)));
    }

    /**
     * 语义向量搜索（POST）
     *
     * <p>流程：查询文本 → 数据面 BGE 嵌入 → Neo4j Vector Index 召回相似对话
     */
    @PostMapping("/search/semantic")
    public ResponseEntity<ApiResponse<List<SemanticSearchResult>>> semanticSearch(
            @Valid @RequestBody SemanticSearchRequest request) {
        List<SemanticSearchResult> results = graphService.semanticSearch(
                request.getQuery(), request.getLimit(), request.getMinScore());
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    /**
     * 语义向量搜索（GET 便捷方式）
     */
    @GetMapping("/search/semantic")
    public ResponseEntity<ApiResponse<List<SemanticSearchResult>>> semanticSearchGet(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0.5") double minScore) {

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("query 不能为空"));
        }
        if (limit < 1 || limit > 50) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("limit 范围为 1~50"));
        }

        SemanticSearchRequest request = new SemanticSearchRequest();
        request.setQuery(query);
        request.setLimit(limit);
        request.setMinScore(minScore);
        return semanticSearch(request);
    }

    /**
     * 数据面回调接口：更新对话处理结果
     *
     * <p>当数据面完成转写/摘要处理后，通过此接口将结果写回 Neo4j。
     *
     * @param conversationId 对话 ID
     * @param result         处理结果（transcript / summary / status）
     * @return 操作结果
     */
    @PutMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> updateConversationResult(
            @PathVariable String conversationId,
            @RequestBody Map<String, String> result) {

        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("conversationId 不能为空"));
        }

        graphService.updateConversationResult(
                conversationId,
                result.get("transcript"),
                result.get("summary"),
                result.getOrDefault("status", "done")
        );
        return ResponseEntity.ok(ApiResponse.success("对话结果已更新",
                Map.of("conversationId", conversationId)));
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    /**
     * 解析 ISO-8601 时间字符串，失败时返回默认值
     *
     * @param timeStr      时间字符串
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private LocalDateTime parseDateTime(String timeStr, LocalDateTime defaultValue) {
        if (timeStr == null || timeStr.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDateTime.parse(timeStr);
        } catch (Exception e) {
            log.warn("时间格式解析失败，使用默认值: timeStr={}", timeStr);
            return defaultValue;
        }
    }
}
