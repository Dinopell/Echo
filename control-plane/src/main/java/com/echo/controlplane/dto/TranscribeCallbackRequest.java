package com.echo.controlplane.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 数据面转写完成回调请求体
 *
 * <p>兼容 camelCase 与 snake_case 字段命名（数据面 Pydantic 序列化）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TranscribeCallbackRequest {

    @JsonAlias({"task_id"})
    private String taskId;

    @JsonAlias({"conversation_id"})
    private String conversationId;

    private String status;

    private String transcript;

    private String summary;

    private String sentiment;

    @JsonAlias({"sentiment_score"})
    private Double sentimentScore;

    private String participants;

    @JsonAlias({"key_persons"})
    private List<String> keyPersons;

    @JsonAlias({"key_topics"})
    private List<String> keyTopics;

    @JsonAlias({"card_object_key"})
    private String cardObjectKey;

    @JsonAlias({"card_url"})
    private String cardUrl;

    private List<Double> embedding;

    @JsonAlias({"error_message"})
    private String errorMessage;
}
