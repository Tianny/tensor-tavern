package com.hify.knowledge.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 更新 Agent 绑定的知识库集合（全量覆盖）。
 */
@Data
public class AgentKnowledgeUpdateReq {

    /** 绑定的知识库 id 列表，空 = 全部解绑 */
    @NotNull(message = "knowledgeBaseIds 不能为 null（空数组表示全部解绑）")
    private List<Long> knowledgeBaseIds;
}
