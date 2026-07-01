package com.echo.controlplane.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 数据面向量嵌入完成回调请求体
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbedCallbackRequest {

    @JsonAlias({"task_id"})
    private String taskId;

    @JsonAlias({"conversation_id"})
    private String conversationId;

    private String status;

    private List<Double> embedding;

    @JsonAlias({"error_message"})
    private String errorMessage;
}
