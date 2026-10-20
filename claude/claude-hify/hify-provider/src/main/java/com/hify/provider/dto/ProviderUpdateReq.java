package com.hify.provider.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 更新 Provider 请求。api_key 留空 = 保持原值（编辑时不回传、不重输密钥）。
 */
@Data
public class ProviderUpdateReq {

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotBlank
    @Size(max = 32)
    private String type;

    @Size(max = 255)
    private String baseUrl;

    /** 留空保持原值 */
    @Size(max = 512)
    private String apiKey;

    /** 留空保持原值 */
    @Min(0)
    @Max(1)
    private Integer enabled;

    @Size(max = 255)
    private String remark;
}
