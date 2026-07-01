package com.echo.controlplane.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 数据面摘要完成回调请求体
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SummarizeCallbackRequest {

    @JsonAlias({"task_id"})
    private String taskId;

    @JsonAlias({"conversation_id"})
    private String conversationId;

    private String status;

    private String summary;

    private String sentiment;

    @JsonAlias({"error_message"})
    private String errorMessage;
}
