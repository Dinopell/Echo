package com.echo.controlplane.controller;

import com.echo.controlplane.dto.ApiResponse;
import com.echo.controlplane.dto.AudioUploadResponse;
import com.echo.controlplane.exception.EchoException;
import com.echo.controlplane.service.AudioService;
import com.echo.controlplane.service.TaskQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 音频处理控制器
 *
 * <p>API 端点：
 * <pre>
 *   POST   /api/v1/audio/upload          — 上传音频文件（AES-256-GCM 加密存储），触发转写流水线
 *   GET    /api/v1/audio/url/{objectKey} — 获取音频文件预签名访问 URL
 *   GET    /api/v1/audio/task/{taskId}   — 查询任务处理进度
 *   GET    /api/v1/audio/queues          — 查看队列状态（调试用）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audio")
@RequiredArgsConstructor
public class AudioController {

    private final AudioService audioService;
    private final TaskQueueService taskQueueService;

    /**
     * 上传音频文件
     *
     * <p>处理流程：
     * <ol>
     *   <li>参数校验（文件非空、格式支持）</li>
     *   <li>AES-256-GCM 加密音频内容</li>
     *   <li>将加密文件存入 MinIO raw-audio bucket</li>
     *   <li>在 Neo4j 创建 ConversationNode</li>
     *   <li>将转写任务推入 Redis 队列</li>
     * </ol>
     *
     * @param file     音频文件（支持 wav/mp3/m4a）
     * @param language 语言提示（auto/zh/en，默认 auto）
     * @return 音频上传响应（含 conversationId、taskId）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AudioUploadResponse>> uploadAudio(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "language", defaultValue = "auto") String language) {

        // 文件非空校验
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("上传文件不能为空"));
        }

        // 语言参数校验
        if (!language.matches("^(auto|zh|en)$")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("language 参数只支持 auto/zh/en"));
        }

        log.info("收到音频上传请求: filename={}, size={}bytes, language={}",
                file.getOriginalFilename(), file.getSize(), language);

        AudioUploadResponse result = audioService.uploadAudio(file, language);
        return ResponseEntity.ok(ApiResponse.success("音频已加密上传，转写任务已入队", result));
    }

    /**
     * 获取音频文件预签名访问 URL
     *
     * @param objectKey   MinIO 对象键（URL 编码后传递）
     * @param expireHours 有效期（小时，默认 1）
     * @return 预签名 URL
     */
    @GetMapping("/url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAudioUrl(
            @RequestParam String objectKey,
            @RequestParam(defaultValue = "1") int expireHours) {

        if (objectKey == null || objectKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("objectKey 不能为空"));
        }
        if (expireHours < 1 || expireHours > 168) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("expireHours 范围为 1~168（最长一周）"));
        }

        String url = audioService.getAudioUrl(objectKey, expireHours);
        return ResponseEntity.ok(ApiResponse.success(Map.of("url", url, "objectKey", objectKey)));
    }

    /**
     * 查询任务处理状态
     *
     * @param taskId Redis 中的任务 ID
     * @return 任务状态信息
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<ApiResponse<Map<Object, Object>>> getTaskStatus(
            @PathVariable String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("taskId 不能为空"));
        }
        Map<Object, Object> status = taskQueueService.getTaskStatus(taskId);
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    /**
     * 查看队列长度（调试/监控用途）
     *
     * @return 各队列的待处理任务数量
     */
    @GetMapping("/queues")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getQueueStats() {
        return ResponseEntity.ok(ApiResponse.success(taskQueueService.getQueueLengths()));
    }
}
