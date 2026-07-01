package com.echo.controlplane.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 语义向量搜索请求
 */
@Data
public class SemanticSearchRequest {

    /** 自然语言查询文本 */
    @NotBlank(message = "query 不能为空")
    private String query;

    /** 返回数量上限，默认 10 */
    @Min(1)
    @Max(50)
    private int limit = 10;

    /** 最低相似度分数（cosine，0~1），默认 0.5 */
    @Min(0)
    @Max(1)
    private double minScore = 0.5;
}
