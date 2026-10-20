package com.hify.provider.dto;

import java.time.LocalDateTime;

import com.hify.provider.entity.ModelConfig;

import lombok.Data;

/**
 * 模型配置响应。
 */
@Data
public class ModelConfigResp {

    private Long id;

    private Long providerId;

    private String modelName;

    private String displayName;

    private String modelType;

    private Integer maxContextTokens;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static ModelConfigResp of(ModelConfig model) {
        ModelConfigResp resp = new ModelConfigResp();
        resp.setId(model.getId());
        resp.setProviderId(model.getProviderId());
        resp.setModelName(model.getModelName());
        resp.setDisplayName(model.getDisplayName());
        resp.setModelType(model.getModelType());
        resp.setMaxContextTokens(model.getMaxContextTokens());
        resp.setEnabled(model.getEnabled());
        resp.setCreatedAt(model.getCreatedAt());
        resp.setUpdatedAt(model.getUpdatedAt());
        return resp;
    }
}
