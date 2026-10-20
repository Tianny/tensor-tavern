package com.hify.workflow.service.impl;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.http.LlmApiException;
import com.hify.common.http.LlmHttpClient;
import com.hify.common.resilience.CircuitBreakerService;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ProviderHttpService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * LLM 节点非流式调用客户端。hify-workflow 不能依赖 hify-chat（依赖方向是
 * chat → workflow），LlmStreamService 复用不了，节点调用按阻塞语义自建：
 * 复用 common 的 LlmHttpClient（超时 60s）+ 按 Provider 隔离的熔断器，
 * 支持 openai / openai_compatible / claude 三种端点。
 *
 * <p>调用方（WorkflowEngine）保证在 llmExecutor 线程上执行。
 */
@Slf4j
@Component
public class LlmNodeClient {

    /** claude 要求 max_tokens 必填且 >0；节点未配置时的兜底值 */
    private static final int CLAUDE_DEFAULT_MAX_TOKENS = 4096;

    private static final String BREAKER_PREFIX = "provider:";

    private final LlmHttpClient httpClient;

    private final ObjectMapper objectMapper;

    private final CircuitBreakerService circuitBreakerService;

    public LlmNodeClient(
            @Qualifier("llmHttpClient") LlmHttpClient httpClient,
            ObjectMapper objectMapper,
            CircuitBreakerService circuitBreakerService) {
        // 显式 @Qualifier：上下文里有两个 LlmHttpClient（默认 + probe），必须点名
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.circuitBreakerService = circuitBreakerService;
    }

    /**
     * 非流式对话调用（单条 user 消息）。
     *
     * @throws LlmApiException 网络 / 认证 / 限流 / 响应异常（熔断器统计）
     */
    public String chat(Provider provider, ModelConfig model, String prompt,
            BigDecimal temperature, int maxTokens) {
        // 非流式无中途输出，失败重试无重复内容风险，允许常规重试
        return circuitBreakerService.execute(BREAKER_PREFIX + provider.getId(),
                () -> doChat(provider, model, prompt, temperature, maxTokens));
    }

    private String doChat(Provider provider, ModelConfig model, String prompt,
            BigDecimal temperature, int maxTokens) {
        return switch (provider.getType()) {
            case "openai", "openai_compatible" -> openaiChat(provider, model, prompt, temperature, maxTokens);
            case "claude" -> claudeChat(provider, model, prompt, temperature, maxTokens);
            default -> throw new LlmApiException(LlmApiException.Kind.CALL_FAILED,
                    provider.getBaseUrl(), null,
                    "提供商类型 " + provider.getType() + " 暂不支持工作流 LLM 节点");
        };
    }

    // ---------- openai / openai_compatible ----------

    private String openaiChat(Provider provider, ModelConfig model, String prompt,
            BigDecimal temperature, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName());
        body.put("messages", java.util.List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", false);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
            headers.put("Authorization", "Bearer " + provider.getApiKey());
        }

        String respBody = httpClient.post(provider.getBaseUrl() + "/chat/completions", headers, body);
        JsonNode content = readTree(respBody).path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new LlmApiException(LlmApiException.Kind.CALL_FAILED,
                    provider.getBaseUrl() + "/chat/completions", null,
                    "响应缺少 choices[0].message.content：" + truncate(respBody));
        }
        return content.asText();
    }

    // ---------- claude ----------

    private String claudeChat(Provider provider, ModelConfig model, String prompt,
            BigDecimal temperature, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName());
        body.put("max_tokens", maxTokens > 0 ? maxTokens : CLAUDE_DEFAULT_MAX_TOKENS);
        body.put("messages", java.util.List.of(Map.of("role", "user", "content", prompt)));
        if (temperature != null) {
            // claude 温度范围 0-1，配置超出截断
            body.put("temperature", temperature.min(BigDecimal.ONE).max(BigDecimal.ZERO));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", provider.getApiKey());
        headers.put("anthropic-version", "2023-06-01");

        String respBody = httpClient.post(provider.getBaseUrl() + "/v1/messages", headers, body);
        // content 为块数组，取第一个 text 块
        JsonNode blocks = readTree(respBody).path("content");
        if (blocks.isArray()) {
            for (JsonNode block : blocks) {
                if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                    return block.path("text").asText();
                }
            }
        }
        throw new LlmApiException(LlmApiException.Kind.CALL_FAILED,
                provider.getBaseUrl() + "/v1/messages", null,
                "响应缺少 content[].text：" + truncate(respBody));
    }

    // ---------- 内部 ----------

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new LlmApiException(LlmApiException.Kind.CALL_FAILED, null, null,
                    "响应不是合法 JSON：" + truncate(body));
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
