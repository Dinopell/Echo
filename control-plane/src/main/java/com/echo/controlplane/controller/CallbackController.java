package com.echo.controlplane.controller;

import com.echo.controlplane.dto.ApiResponse;
import com.echo.controlplane.dto.EmbedCallbackRequest;
import com.echo.controlplane.dto.SummarizeCallbackRequest;
import com.echo.controlplane.dto.TranscribeCallbackRequest;
import com.echo.controlplane.exception.EchoException;
import com.echo.controlplane.service.TaskQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据面回调控制器
 *
 * <p>数据面（data-plane）完成 AI 处理后，通过此接口将结果推送给控制面。
 * 控制面收到回调后更新 Redis 任务状态和 Neo4j 图谱。
 */
@Slf4j
@RestController
@RequestMapping("/api/callback")
@RequiredArgsConstructor
public class CallbackController {

    private final TaskQueueService taskQueueService;

    /**
     * 接收数据面转写完成回调
     */
    @PostMapping("/transcribe-result")
    public ResponseEntity<ApiResponse<Map<String, String>>> handleTranscribeResult(
            @RequestBody TranscribeCallbackRequest request) {

        if (request.getTaskId() == null || request.getTaskId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("taskId 不能为空"));
        }
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("conversationId 不能为空"));
        }

        String taskId = request.getTaskId();
        String conversationId = request.getConversationId();
        String status = request.getStatus() != null ? request.getStatus() : "done";

        log.info("收到转写回调: taskId={}, conversationId={}, status={}", taskId, conversationId, status);

        if ("failed".equals(status)) {
            taskQueueService.markTaskFailed(taskId, request.getErrorMessage());
            taskQueueService.updateConversationStatus(conversationId, "failed");
            return ResponseEntity.ok(ApiResponse.success("失败状态已记录",
                    Map.of("taskId", taskId, "status", "failed")));
        }

        try {
            taskQueueService.handleTranscribeCallback(request);
        } catch (EchoException e) {
            log.error("转写回调处理失败: taskId={}, error={}", taskId, e.getMessage());
            return ResponseEntity.status(e.getHttpStatus())
                    .body(ApiResponse.<Map<String, String>>builder()
                            .code(e.getErrorCode())
                            .message(e.getMessage())
                            .build());
        }

        return ResponseEntity.ok(ApiResponse.success("转写结果已处理",
                Map.of("taskId", taskId, "conversationId", conversationId, "status", "done")));
    }

    /**
     * 接收数据面摘要完成回调
     */
    @PostMapping("/summarize-result")
    public ResponseEntity<ApiResponse<Map<String, String>>> handleSummarizeResult(
            @RequestBody SummarizeCallbackRequest request) {

        if (request.getTaskId() == null || request.getTaskId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("taskId 不能为空"));
        }
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("conversationId 不能为空"));
        }

        String taskId = request.getTaskId();
        String conversationId = request.getConversationId();
        String status = request.getStatus() != null ? request.getStatus() : "done";

        log.info("收到摘要回调: taskId={}, conversationId={}, status={}", taskId, conversationId, status);

        if ("failed".equals(status)) {
            taskQueueService.markTaskFailed(taskId, request.getErrorMessage());
            return ResponseEntity.ok(ApiResponse.success("失败状态已记录",
                    Map.of("taskId", taskId, "status", "failed")));
        }

        try {
            taskQueueService.handleSummarizeResult(
                    taskId,
                    conversationId,
                    request.getSummary(),
                    request.getSentiment()
            );
        } catch (EchoException e) {
            log.error("摘要回调处理失败: taskId={}, error={}", taskId, e.getMessage());
            return ResponseEntity.status(e.getHttpStatus())
                    .body(ApiResponse.<Map<String, String>>builder()
                            .code(e.getErrorCode())
                            .message(e.getMessage())
                            .build());
        }

        return ResponseEntity.ok(ApiResponse.success("摘要结果已处理",
                Map.of("taskId", taskId, "conversationId", conversationId, "status", "done")));
    }

    /**
     * 接收向量嵌入完成回调
     */
    @PostMapping("/embed-result")
    public ResponseEntity<ApiResponse<Map<String, String>>> handleEmbedResult(
            @RequestBody EmbedCallbackRequest request) {

        String taskId = request.getTaskId();
        log.info("收到向量嵌入回调: taskId={}, conversationId={}", taskId, request.getConversationId());

        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("taskId 不能为空"));
        }

        if ("failed".equals(request.getStatus())) {
            taskQueueService.markTaskFailed(taskId, request.getErrorMessage());
            return ResponseEntity.ok(ApiResponse.success("失败状态已记录",
                    Map.of("taskId", taskId, "status", "failed")));
        }

        if (request.getConversationId() != null && request.getEmbedding() != null
                && !request.getEmbedding().isEmpty()) {
            taskQueueService.handleEmbedResult(request.getConversationId(), request.getEmbedding());
        }

        return ResponseEntity.ok(ApiResponse.success("嵌入结果已处理",
                Map.of("taskId", taskId, "status", "done")));
    }
}
