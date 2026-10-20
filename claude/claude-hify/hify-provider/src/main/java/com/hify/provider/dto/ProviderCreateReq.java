package com.hify.provider.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建 Provider 请求。
 */
@Data
public class ProviderCreateReq {

    @NotBlank
    @Size(max = 64)
    private String name;

    /** 提供商类型（ProviderType.code） */
    @NotBlank
    @Size(max = 32)
    private String type;

    /** API 地址；openai_compatible 必填，其余类型可空（用官方默认） */
    @Size(max = 255)
    private String baseUrl;

    /** API 密钥；openai / claude / gemini 必填 */
    @Size(max = 512)
    private String apiKey;

    /** 是否启用，可空默认 1 */
    @Min(0)
    @Max(1)
    private Integer enabled;

    @Size(max = 255)
    private String remark;
}
