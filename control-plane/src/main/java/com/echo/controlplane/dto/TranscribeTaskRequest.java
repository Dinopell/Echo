package com.echo.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 转写任务请求 DTO
 *
 * <p>客户端通过此 DTO 直接提交转写任务（适用于已上传到 MinIO 的音频文件）。
 * 与音频上传接口配合使用：先上传文件获取 objectKey，再通过此接口手动触发任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscribeTaskRequest {

    /** 音频文件在 MinIO 中的对象键（必填） */
    @NotBlank(message = "audioObjectKey 不能为空")
    private String audioObjectKey;

    /** 所在 bucket 名称（默认 raw-audio） */
    @Builder.Default
    private String bucket = "raw-audio";

    /** 目标语言（auto / zh / en） */
    @Pattern(regexp = "^(auto|zh|en)$", message = "language 只支持 auto/zh/en")
    @Builder.Default
    private String language = "auto";

    /** 关联的对话 ID（可选，若不填则自动生成） */
    private String conversationId;

    /** 任务优先级（0=普通，1=高优先） */
    @Builder.Default
    private Integer priority = 0;
}
