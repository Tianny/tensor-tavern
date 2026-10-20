package com.hify.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建知识库请求。
 */
@Data
public class KnowledgeBaseCreateReq {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 64, message = "知识库名称最长 64 字")
    private String name;

    @Size(max = 255, message = "描述最长 255 字")
    private String description;

    @NotNull(message = "必须选择向量化模型")
    private Long embeddingModelId;
}
