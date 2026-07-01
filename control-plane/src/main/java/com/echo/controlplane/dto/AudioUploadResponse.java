package com.echo.controlplane.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 音频上传响应 DTO
 *
 * <p>音频上传成功后，返回对话 ID、任务 ID 及对象存储路径。
 * 客户端可通过 taskId 轮询处理状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioUploadResponse {

    /** 新创建的对话节点 ID */
    private String conversationId;

    /** Redis 任务 ID（用于查询转写进度） */
    private String taskId;

    /** MinIO 中音频文件的对象键 */
    private String objectKey;

    /** 任务当前状态（初始为 pending） */
    @Builder.Default
    private String status = "pending";

    /** 提示消息 */
    private String message;
}
