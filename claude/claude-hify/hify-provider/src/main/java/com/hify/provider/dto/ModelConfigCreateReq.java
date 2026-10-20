package com.hify.provider.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建模型配置请求。
 */
@Data
public class ModelConfigCreateReq {

    @NotNull
    @Positive
    private Long providerId;

    @NotBlank
    @Size(max = 64)
    private String modelName;

    /** 展示名称，可空默认取 modelName */
    @Size(max = 64)
    private String displayName;

    /** chat / embedding */
    @NotBlank
    @Size(max = 16)
    private String modelType;

    /** 上下文窗口 token 上限，可空默认 0（未知） */
    @Min(0)
    private Integer maxContextTokens;

    /** 是否启用，可空默认 1 */
    @Min(0)
    private Integer enabled;
}
