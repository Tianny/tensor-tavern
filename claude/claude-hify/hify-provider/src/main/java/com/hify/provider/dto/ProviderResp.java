package com.hify.provider.dto;

import java.time.LocalDateTime;

import com.hify.provider.entity.Provider;

import lombok.Data;

/**
 * Provider 响应。api_key 只回掩码（明文永不出服务端）。
 */
@Data
public class ProviderResp {

    private Long id;

    private String name;

    private String type;

    private String baseUrl;

    /** 掩码后的 API Key，如 "****ab3f"；未配置为空串 */
    private String apiKeyMasked;

    private Integer enabled;

    private String remark;

    private LocalDateTime lastTestAt;

    /** 0=失败 1=成功，null=从未测试 */
    private Integer lastTestOk;

    private String lastTestMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static ProviderResp of(Provider provider) {
        ProviderResp resp = new ProviderResp();
        resp.setId(provider.getId());
        resp.setName(provider.getName());
        resp.setType(provider.getType());
        resp.setBaseUrl(provider.getBaseUrl());
        resp.setApiKeyMasked(maskApiKey(provider.getApiKey()));
        resp.setEnabled(provider.getEnabled());
        resp.setRemark(provider.getRemark());
        resp.setLastTestAt(provider.getLastTestAt());
        resp.setLastTestOk(provider.getLastTestOk());
        resp.setLastTestMessage(provider.getLastTestMessage());
        resp.setCreatedAt(provider.getCreatedAt());
        resp.setUpdatedAt(provider.getUpdatedAt());
        return resp;
    }

    /** 只露尾 4 位；过短的值整体打码 */
    static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }
}
