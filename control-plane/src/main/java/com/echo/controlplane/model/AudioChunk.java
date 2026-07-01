package com.echo.controlplane.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 音频分块数据模型
 *
 * <p>用于接收客户端上传的音频数据元信息。
 * 实际音频二进制内容由 AudioController 接收后直接流式写入 MinIO，
 * 此对象仅携带元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioChunk {

    /** 分块唯一 ID */
    private String chunkId;

    /** 所属会话 ID */
    private String sessionId;

    /** 分块序号（从 0 开始） */
    private Integer chunkIndex;

    /** 音频格式（wav / mp3 / m4a） */
    private String format;

    /** 分块时长（毫秒） */
    private Long durationMs;

    /** MinIO 存储路径 */
    private String objectKey;

    /** 上传时间 */
    private LocalDateTime uploadedAt;

    /** 是否为最后一个分块 */
    @Builder.Default
    private Boolean isLast = false;
}
