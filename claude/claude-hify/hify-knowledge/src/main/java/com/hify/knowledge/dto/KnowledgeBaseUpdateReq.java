package com.hify.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 更新知识库请求。embeddingModelId 不可改（库内向量维度与模型绑定，换模型需重刷全量向量）。
 */
@Data
public class KnowledgeBaseUpdateReq {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 64, message = "知识库名称最长 64 字")
    private String name;

    @Size(max = 255, message = "描述最长 255 字")
    private String description;
}
