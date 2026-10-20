package com.hify.provider.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 更新模型配置请求。不支持换 provider（删除后在新 provider 下重建）。
 */
@Data
public class ModelConfigUpdateReq {

    @NotBlank
    @Size(max = 64)
    private String modelName;

    @Size(max = 64)
    private String displayName;

    /** chat / embedding */
    @NotBlank
    @Size(max = 16)
    private String modelType;

    @Min(0)
    private Integer maxContextTokens;

    @Min(0)
    private Integer enabled;
}
