package com.hify.provider.constant;

import lombok.Getter;

/**
 * 提供商类型：决定鉴权头、请求路径和默认地址（即 adapter 的选择键）。
 *
 * <p>base_url 约定（存库值）：
 * <ul>
 *   <li>openai / openai_compatible：存到版本前缀一级（如 https://api.openai.com/v1），
 *       请求时追加 /models、/chat/completions</li>
 *   <li>claude：存根地址（https://api.anthropic.com），追加 /v1/messages、/v1/models</li>
 *   <li>gemini：存根地址，追加 /v1beta/models/{model}:generateContent</li>
 *   <li>ollama：存根地址（http://localhost:11434），探测用 /api/tags，对话走 /v1 OpenAI 兼容端点</li>
 * </ul>
 */
@Getter
public enum ProviderType {

    /** OpenAI 官方 */
    OPENAI("openai", "https://api.openai.com/v1", true),

    /**
     * OpenAI 兼容端点：DeepSeek / 通义 / GLM / Kimi / vLLM / OpenRouter / 中转站等。
     * 无官方默认地址，base_url 必填；API Key 不强制（本地 vLLM 可无 Key）。
     */
    OPENAI_COMPATIBLE("openai_compatible", "", false),

    /** Anthropic Claude 官方 */
    CLAUDE("claude", "https://api.anthropic.com", true),

    /** Google Gemini 官方 */
    GEMINI("gemini", "https://generativelanguage.googleapis.com", true),

    /** Ollama 本地部署，无需 API Key */
    OLLAMA("ollama", "http://localhost:11434", false);

    private final String code;

    /** 官方默认地址；空串表示没有默认值（base_url 必填） */
    private final String defaultBaseUrl;

    /** 该类型是否必须配置 API Key */
    private final boolean apiKeyRequired;

    ProviderType(String code, String defaultBaseUrl, boolean apiKeyRequired) {
        this.code = code;
        this.defaultBaseUrl = defaultBaseUrl;
        this.apiKeyRequired = apiKeyRequired;
    }

    /** 按存库 code 解析；未匹配返回 null（由调用方抛 PROVIDER_TYPE_INVALID） */
    public static ProviderType of(String code) {
        for (ProviderType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
