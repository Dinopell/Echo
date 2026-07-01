package com.echo.controlplane.service;

import com.echo.controlplane.dto.TranscribeCallbackRequest;
import com.echo.controlplane.exception.EchoException;
import com.echo.controlplane.model.TranscribeTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 任务队列服务
 *
 * <p>负责控制面与数据面之间的任务通信：
 * <ul>
 *   <li>控制面 → Redis 队列（List）→ 数据面（任务分发，LPUSH/BRPOP FIFO）</li>
 *   <li>数据面 → Redis Hash → 控制面（结果回调，写入结果后触发图谱更新）</li>
 * </ul>
 *
 * <p>Redis Key 规范（统一前缀 "echo:"）：
 * <pre>
 *   echo:queue:transcribe  — 转写任务队列（List）
 *   echo:queue:summarize   — 摘要任务队列（List）
 *   echo:queue:embed       — 向量嵌入任务队列（List）
 *   echo:result:{taskId}   — 任务结果 Hash（24h 过期）
 *   echo:task:{taskId}     — 任务元数据 Hash（24h 过期）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final GraphService graphService;

    @Value("${echo.redis.queue.transcribe:echo:queue:transcribe}")
    private String transcribeQueue;

    @Value("${echo.redis.queue.summarize:echo:queue:summarize}")
    private String summarizeQueue;

    @Value("${echo.redis.queue.embed:echo:queue:embed}")
    private String embedQueue;

    @Value("${echo.redis.result.prefix:echo:result:}")
    private String resultKeyPrefix;

    /** 任务元数据 Key 前缀 */
    private static final String TASK_META_PREFIX = "echo:task:";

    /** 任务状态 Key 过期时间（小时） */
    private static final long TASK_TTL_HOURS = 24;

    // ── 任务投递 ──────────────────────────────────────────────

    /**
     * 推送转写任务到 Redis 队列（兼容旧方法名）
     *
     * @param task 转写任务对象
     */
    public void pushTranscribeTask(TranscribeTask task) {
        submitTranscribeTask(task);
    }

    /**
     * 提交转写任务到 Redis 队列
     *
     * <p>流程：
     * <ol>
     *   <li>序列化任务对象为 JSON</li>
     *   <li>LPUSH 到 echo:queue:transcribe 队列</li>
     *   <li>初始化任务状态到 echo:result:{taskId}</li>
     * </ol>
     *
     * @param task 转写任务对象
     */
    public void submitTranscribeTask(TranscribeTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            // LPUSH 添加到列表头部（数据面 BRPOP 从尾部取，实现 FIFO）
            redisTemplate.opsForList().leftPush(transcribeQueue, taskJson);
            // 初始化任务元数据
            initTaskMeta(task.getTaskId(), "transcribe", task.getConversationId());
            // 初始化任务状态
            updateTaskStatus(task.getTaskId(), "pending", null, null);

            log.info("转写任务已入队: taskId={}, conversationId={}, queue={}",
                    task.getTaskId(), task.getConversationId(), transcribeQueue);
        } catch (EchoException e) {
            throw e;
        } catch (Exception e) {
            log.error("推送转写任务失败: taskId={}", task.getTaskId(), e);
            throw new EchoException(EchoException.ErrorCode.TASK_ENQUEUE_FAILED, e.getMessage());
        }
    }

    /**
     * 根据请求 DTO 构建并提交转写任务
     *
     * @param audioObjectKey MinIO 音频对象键
     * @param bucket         bucket 名称
     * @param language       语言
     * @param conversationId 对话 ID（可选，为空则自动生成）
     * @param priority       优先级
     * @return 新建的任务 ID
     */
    public String submitTranscribeTask(String audioObjectKey, String bucket,
                                        String language, String conversationId, int priority) {
        String taskId = UUID.randomUUID().toString();
        String convId = conversationId != null ? conversationId : UUID.randomUUID().toString();

        TranscribeTask task = TranscribeTask.builder()
                .taskId(taskId)
                .conversationId(convId)
                .audioObjectKey(audioObjectKey)
                .bucket(bucket)
                .language(language != null ? language : "auto")
                .createdAt(LocalDateTime.now())
                .status("pending")
                .priority(priority)
                .build();

        submitTranscribeTask(task);
        return taskId;
    }

    // ── 任务状态查询 ──────────────────────────────────────────

    /**
     * 查询任务处理状态
     *
     * @param taskId 任务 ID
     * @return 任务状态 Map（含 status、result 等字段）
     */
    public Map<Object, Object> getTaskStatus(String taskId) {
        String resultKey = resultKeyPrefix + taskId;
        Map<Object, Object> result = redisTemplate.opsForHash().entries(resultKey);
        if (result == null || result.isEmpty()) {
            // 任务不存在时返回 unknown 状态
            Map<Object, Object> defaultStatus = new HashMap<>();
            defaultStatus.put("status", "unknown");
            defaultStatus.put("taskId", taskId);
            defaultStatus.put("message", "任务不存在或已过期");
            return defaultStatus;
        }
        return result;
    }

    /**
     * 获取各队列的待处理任务数量
     *
     * @return 队列长度 Map
     */
    public Map<String, Long> getQueueLengths() {
        Map<String, Long> lengths = new HashMap<>();
        lengths.put(transcribeQueue, sizeOf(transcribeQueue));
        lengths.put(summarizeQueue, sizeOf(summarizeQueue));
        lengths.put(embedQueue, sizeOf(embedQueue));
        return lengths;
    }

    private long sizeOf(String queueKey) {
        Long len = redisTemplate.opsForList().size(queueKey);
        return len != null ? len : 0L;
    }

    // ── 回调结果处理 ──────────────────────────────────────────

    /**
     * 处理数据面转写流水线完整回调
     */
    public void handleTranscribeCallback(TranscribeCallbackRequest request) {
        String taskId = request.getTaskId();
        String conversationId = request.getConversationId();
        log.info("收到转写回调结果: taskId={}, conversationId={}", taskId, conversationId);

        // 1. 更新 Redis 任务状态（供客户端轮询）
        String resultKey = resultKeyPrefix + taskId;
        redisTemplate.opsForHash().put(resultKey, "task_id", taskId);
        redisTemplate.opsForHash().put(resultKey, "status", "done");
        redisTemplate.opsForHash().put(resultKey, "updatedAt", LocalDateTime.now().toString());
        if (request.getTranscript() != null) {
            redisTemplate.opsForHash().put(resultKey, "transcript_length",
                    String.valueOf(request.getTranscript().length()));
        }
        if (request.getSentiment() != null) {
            redisTemplate.opsForHash().put(resultKey, "sentiment", request.getSentiment());
        }
        if (request.getSentimentScore() != null) {
            redisTemplate.opsForHash().put(resultKey, "sentiment_score",
                    String.valueOf(request.getSentimentScore()));
        }
        redisTemplate.expire(resultKey, TASK_TTL_HOURS, TimeUnit.HOURS);

        // 2. 解析参与者（优先 participants，其次 keyPersons）
        String participants = request.getParticipants();
        if ((participants == null || participants.isBlank())
                && request.getKeyPersons() != null && !request.getKeyPersons().isEmpty()) {
            participants = String.join(",", request.getKeyPersons());
        }

        // 3. 更新 Neo4j 图谱
        try {
            graphService.updateConversationFromCallback(request);

            if (participants != null && !participants.isBlank()) {
                double sentimentScore = request.getSentimentScore() != null
                        ? request.getSentimentScore()
                        : parseSentimentScore(request.getSentiment());
                graphService.updateGraph(conversationId, participants.split(","),
                        sentimentScore, request.getSummary());
            }
            log.info("图谱更新成功: conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("图谱更新失败（不影响任务完成）: conversationId={}", conversationId, e);
            redisTemplate.opsForHash().put(resultKey, "graphUpdateError", e.getMessage());
        }
    }

    /**
     * 标记任务失败
     */
    public void markTaskFailed(String taskId, String errorMessage) {
        String resultKey = resultKeyPrefix + taskId;
        redisTemplate.opsForHash().put(resultKey, "task_id", taskId);
        redisTemplate.opsForHash().put(resultKey, "status", "failed");
        redisTemplate.opsForHash().put(resultKey, "error",
                errorMessage != null ? errorMessage : "处理失败");
        redisTemplate.opsForHash().put(resultKey, "updatedAt", LocalDateTime.now().toString());
        redisTemplate.expire(resultKey, TASK_TTL_HOURS, TimeUnit.HOURS);
        log.warn("任务标记为失败: taskId={}, error={}", taskId, errorMessage);
    }

    /**
     * 更新对话节点状态（失败场景）
     */
    public void updateConversationStatus(String conversationId, String status) {
        try {
            graphService.updateConversationResult(conversationId, null, null, status);
        } catch (Exception e) {
            log.error("更新对话状态失败: conversationId={}", conversationId, e);
        }
    }

    /**
     * 处理向量嵌入回调，写入对话节点
     */
    public void handleEmbedResult(String conversationId, List<Double> embedding) {
        graphService.updateConversationEmbedding(conversationId, embedding);
        log.info("向量嵌入已写入图谱: conversationId={}, dimension={}",
                conversationId, embedding.size());
    }

    /**
     * 处理数据面回调的转写完成结果（兼容旧接口）
     */
    public void handleTaskResult(String taskId, String conversationId, String transcript,
                                  String summary, String sentiment, String participants) {
        TranscribeCallbackRequest request = new TranscribeCallbackRequest();
        request.setTaskId(taskId);
        request.setConversationId(conversationId);
        request.setStatus("done");
        request.setTranscript(transcript);
        request.setSummary(summary);
        request.setSentiment(sentiment);
        request.setParticipants(participants);
        handleTranscribeCallback(request);
    }

    /**
     * 处理数据面回调的摘要完成结果
     *
     * @param taskId         任务 ID
     * @param conversationId 对话 ID
     * @param summary        AI 摘要文本
     * @param sentiment      情感标签（positive / negative / neutral）
     */
    public void handleSummarizeResult(String taskId, String conversationId,
                                       String summary, String sentiment) {
        log.info("收到摘要回调结果: taskId={}, conversationId={}", taskId, conversationId);

        // 更新 Redis 中的摘要结果
        String resultKey = resultKeyPrefix + taskId;
        redisTemplate.opsForHash().put(resultKey, "summary", summary != null ? summary : "");
        redisTemplate.opsForHash().put(resultKey, "sentiment", sentiment != null ? sentiment : "neutral");
        redisTemplate.opsForHash().put(resultKey, "summaryStatus", "done");
        redisTemplate.expire(resultKey, TASK_TTL_HOURS, TimeUnit.HOURS);

        // 更新 Neo4j ConversationNode 的摘要字段
        try {
            graphService.updateConversationResult(conversationId, null, summary, "done");
            log.info("对话摘要已更新到图谱: conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("更新摘要到图谱失败: conversationId={}", conversationId, e);
        }
    }

    // ── 私有辅助方法 ──────────────────────────────────────────

    /**
     * 初始化任务元数据
     *
     * @param taskId         任务 ID
     * @param taskType       任务类型（transcribe/summarize/embed）
     * @param conversationId 关联对话 ID
     */
    private void initTaskMeta(String taskId, String taskType, String conversationId) {
        String metaKey = TASK_META_PREFIX + taskId;
        redisTemplate.opsForHash().put(metaKey, "taskId", taskId);
        redisTemplate.opsForHash().put(metaKey, "taskType", taskType);
        redisTemplate.opsForHash().put(metaKey, "conversationId", conversationId != null ? conversationId : "");
        redisTemplate.opsForHash().put(metaKey, "createdAt", LocalDateTime.now().toString());
        redisTemplate.expire(metaKey, TASK_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 更新任务状态到 Redis
     *
     * @param taskId     任务 ID
     * @param status     状态值（pending/processing/done/failed）
     * @param transcript 转写文本（可为 null）
     * @param summary    摘要文本（可为 null）
     */
    private void updateTaskStatus(String taskId, String status, String transcript, String summary) {
        String resultKey = resultKeyPrefix + taskId;
        redisTemplate.opsForHash().put(resultKey, "taskId", taskId);
        redisTemplate.opsForHash().put(resultKey, "status", status);
        redisTemplate.opsForHash().put(resultKey, "updatedAt", LocalDateTime.now().toString());
        if (transcript != null) {
            redisTemplate.opsForHash().put(resultKey, "transcript", transcript);
        }
        if (summary != null) {
            redisTemplate.opsForHash().put(resultKey, "summary", summary);
        }
        redisTemplate.expire(resultKey, TASK_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 将情感标签转换为数值分数
     *
     * @param sentiment 情感标签（positive/negative/neutral）
     * @return 情感分数（-1.0 ~ 1.0）
     */
    private double parseSentimentScore(String sentiment) {
        if (sentiment == null) return 0.0;
        return switch (sentiment.toLowerCase()) {
            case "positive" -> 0.8;
            case "negative" -> -0.8;
            default -> 0.0;
        };
    }
}
