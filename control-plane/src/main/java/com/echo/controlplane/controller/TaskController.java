package com.echo.controlplane.controller;

import com.echo.controlplane.dto.ApiResponse;
import com.echo.controlplane.dto.TranscribeTaskRequest;
import com.echo.controlplane.service.TaskQueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务管理控制器
 *
 * <p>API 端点：
 * <pre>
 *   POST   /api/v1/tasks/transcribe       — 手动投递转写任务到 Redis 队列
 *   GET    /api/v1/tasks/{taskId}         — 查询单个任务状态
 *   GET    /api/v1/tasks/queues           — 查询所有队列统计信息
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskQueueService taskQueueService;

    /**
     * 手动投递转写任务到 Redis 队列
     *
     * <p>适用场景：音频文件已通过其他方式上传到 MinIO，需要手动触发转写。
     * 正常上传流程由 AudioController.uploadAudio() 自动触发，无需调用此接口。
     *
     * @param request 转写任务请求体（包含 audioObjectKey、language 等）
     * @return 新建任务 ID
     */
    @PostMapping("/transcribe")
    public ResponseEntity<ApiResponse<Map<String, String>>> submitTranscribeTask(
            @Valid @RequestBody TranscribeTaskRequest request) {

        log.info("手动投递转写任务: objectKey={}, language={}, priority={}",
                request.getAudioObjectKey(), request.getLanguage(), request.getPriority());

        String taskId = taskQueueService.submitTranscribeTask(
                request.getAudioObjectKey(),
                request.getBucket(),
                request.getLanguage(),
                request.getConversationId(),
                request.getPriority() != null ? request.getPriority() : 0
        );

        return ResponseEntity.ok(ApiResponse.success("转写任务已入队",
                Map.of("taskId", taskId, "status", "pending")));
    }

    /**
     * 查询任务状态
     *
     * @param taskId 任务 ID
     * @return 任务状态（status / result / errorMessage 等）
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<Object, Object>>> getTaskStatus(
            @PathVariable String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("taskId 不能为空"));
        }
        log.debug("查询任务状态: taskId={}", taskId);
        return ResponseEntity.ok(ApiResponse.success(taskQueueService.getTaskStatus(taskId)));
    }

    /**
     * 获取所有队列的统计信息
     *
     * @return 各队列待处理任务数量
     */
    @GetMapping("/queues")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getQueueStats() {
        return ResponseEntity.ok(ApiResponse.success(taskQueueService.getQueueLengths()));
    }
}
