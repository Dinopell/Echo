package com.echo.controlplane.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 图谱查询请求 DTO
 *
 * <p>支持多维度查询：
 * <ul>
 *   <li>按人名（personName）</li>
 *   <li>按时间范围（startTime ~ endTime）</li>
 *   <li>按话题关键词（topic）</li>
 * </ul>
 *
 * <p>多个条件同时指定时，以 AND 逻辑组合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphQueryRequest {

    /** 按人名查询（模糊匹配） */
    private String personName;

    /** 查询起始时间 */
    private LocalDateTime startTime;

    /** 查询截止时间 */
    private LocalDateTime endTime;

    /** 话题关键词（匹配 summary 或 transcript 中的内容） */
    private String topic;

    /** 返回结果数量上限（默认 20） */
    @Builder.Default
    private Integer limit = 20;

    /** 是否包含转写全文（默认不包含，减少响应体大小） */
    @Builder.Default
    private Boolean includeTranscript = false;
}
