package com.echo.controlplane.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 转写任务模型
 *
 * <p>控制面将此对象序列化为 JSON 后推送到 Redis 队列（echo:queue:transcribe）。
 * 数据面的 task_consumer.py 消费此消息后调用 WhisperX 执行转写。
 *
 * <p>任务状态流转：
 * <pre>
 *   pending → processing → done / failed
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscribeTask {

    /** 任务唯一 ID（UUID） */
    private String taskId;

    /** 对话 ID */
    private String conversationId;

    /** MinIO 中音频文件的对象键 */
    private String audioObjectKey;

    /** 所在 bucket 名称 */
    private String bucket;

    /** 期望使用的语言（zh / en / auto） */
    @Builder.Default
    private String language = "auto";

    /** 任务创建时间 */
    private LocalDateTime createdAt;

    /** 任务当前状态 */
    @Builder.Default
    private String status = "pending";

    /** 失败原因（仅 failed 状态时有值） */
    private String errorMessage;

    /** 优先级（0=普通，1=高优先） */
    @Builder.Default
    private Integer priority = 0;
}
