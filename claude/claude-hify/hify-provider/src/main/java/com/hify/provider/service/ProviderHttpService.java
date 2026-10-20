package com.hify.provider.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.hify.common.exception.BizException;
import com.hify.common.http.LlmApiException;
import com.hify.common.http.LlmHttpClient;
import com.hify.provider.constant.ProviderErrorCode;
import com.hify.provider.constant.ProviderType;
import com.hify.provider.entity.Provider;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Provider 出站 HTTP 公共链路：连通性测试与拉取模型列表共用同一批能力——
 * 按 {@link ProviderType} 拼各家的 models 端点与鉴权头，在 llmExecutor 上执行 GET
 * （CLAUDE.md：LLM 调用不占业务请求线程）。不进熔断/重试：探测要快速反馈，
 * 熔断保护的是 chat 的自动调用链路。
 */
@Slf4j
@Service
public class ProviderHttpService {

    private final LlmHttpClient probeClient;

    private final ThreadPoolTaskExecutor llmExecutor;

    public ProviderHttpService(
            @Qualifier("llmProbeHttpClient") LlmHttpClient probeClient,
            @Qualifier("llmExecutor") ThreadPoolTaskExecutor llmExecutor) {
        this.probeClient = probeClient;
        this.llmExecutor = llmExecutor;
    }

    /** base_url 留空用类型默认地址；无默认（openai_compatible）抛 2004 */
    public String resolveBaseUrl(ProviderType type, String baseUrl) {
        if (StringUtils.hasText(baseUrl)) {
            return baseUrl.trim();
        }
        if (StringUtils.hasText(type.getDefaultBaseUrl())) {
            return type.getDefaultBaseUrl();
        }
        throw new BizException(ProviderErrorCode.PROVIDER_BASE_URL_REQUIRED,
                "类型 " + type.getCode() + " 没有官方默认地址，必须填写 Base URL");
    }

    /**
     * GET 提供商的模型列表端点，返回响应体。
     * 失败抛 {@link LlmApiException}（网络/超时/401/429 已分类）。
     */
    public String fetchModelsBody(Provider provider) {
        ProviderType type = ProviderType.of(provider.getType());
        if (type == null) {
            throw new BizException(ProviderErrorCode.PROVIDER_TYPE_INVALID,
                    "不支持的类型：" + provider.getType());
        }
        String baseUrl = resolveBaseUrl(type, provider.getBaseUrl());
        String url = switch (type) {
            case OPENAI, OPENAI_COMPATIBLE -> baseUrl + "/models";
            case CLAUDE -> baseUrl + "/v1/models";
            case GEMINI -> baseUrl + "/v1beta/models";
            case OLLAMA -> baseUrl + "/api/tags";
        };
        Map<String, String> headers = authHeaders(type, provider.getApiKey());
        try {
            return CompletableFuture.supplyAsync(() -> probeClient.get(url, headers), llmExecutor)
                    .join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : e;
        }
    }

    /** 失败原因转用户可读文案（test-connection / 拉取模型列表共用） */
    public static String describeFailure(Throwable cause) {
        if (cause instanceof LlmApiException exception) {
            return switch (exception.getKind()) {
                case TIMEOUT -> "连接超时，请检查地址与网络";
                case AUTH_FAILED -> "认证失败，请检查 API Key";
                case RATE_LIMITED -> "被限流（429），请稍后再试";
                case CALL_FAILED -> "调用失败：" + exception.getMessage();
            };
        }
        return "调用失败：" + cause;
    }

    private Map<String, String> authHeaders(ProviderType type, String apiKey) {
        Map<String, String> headers = new HashMap<>();
        switch (type) {
            case OPENAI, OPENAI_COMPATIBLE -> {
                if (StringUtils.hasText(apiKey)) {
                    headers.put("Authorization", "Bearer " + apiKey);
                }
            }
            case CLAUDE -> {
                headers.put("x-api-key", apiKey);
                headers.put("anthropic-version", "2023-06-01");
            }
            case GEMINI -> headers.put("x-goog-api-key", apiKey);
            case OLLAMA -> {
                // 无鉴权
            }
        }
        return headers;
    }
}
