package com.echo.controlplane.dto;

import com.echo.controlplane.model.ConversationNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语义搜索单条结果（对话节点 + 相似度分数）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticSearchResult {

    private ConversationNode conversation;

    /** 余弦相似度分数（越高越相关） */
    private double score;
}
