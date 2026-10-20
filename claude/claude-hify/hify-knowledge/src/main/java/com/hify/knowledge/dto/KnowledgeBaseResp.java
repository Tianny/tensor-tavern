package com.hify.knowledge.dto;

import java.time.LocalDateTime;

import com.hify.knowledge.entity.KnowledgeBase;

import lombok.Data;

/**
 * 知识库响应（联表向量化模型信息）。
 */
@Data
public class KnowledgeBaseResp {

    private Long id;

    private String name;

    private String description;

    private Long embeddingModelId;

    /** 向量化模型展示名，模型被删则空串 */
    private String embeddingModelName;

    /** 向量化模型所属提供商名，被删则空串 */
    private String embeddingProviderName;

    private LocalDateTime createdAt;

    public static KnowledgeBaseResp of(KnowledgeBase kb, String modelName, String providerName) {
        KnowledgeBaseResp resp = new KnowledgeBaseResp();
        resp.setId(kb.getId());
        resp.setName(kb.getName());
        resp.setDescription(kb.getDescription() == null ? "" : kb.getDescription());
        resp.setEmbeddingModelId(kb.getEmbeddingModelId());
        resp.setEmbeddingModelName(modelName == null ? "" : modelName);
        resp.setEmbeddingProviderName(providerName == null ? "" : providerName);
        resp.setCreatedAt(kb.getCreatedAt());
        return resp;
    }
}
