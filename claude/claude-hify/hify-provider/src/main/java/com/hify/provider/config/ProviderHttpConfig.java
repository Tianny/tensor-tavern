package com.hify.provider.config;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.http.LlmHttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider 模块 HTTP 配置。
 *
 * <p>连通性测试 / 模型列表拉取用独立的 {@link LlmHttpClient} 实例（llmProbeHttpClient）：
 * 读超时 10s（CLAUDE.md：连通性测试 10s 超时），与对话调用（60s）分开配置。
 * 注意容器里因此有两个 LlmHttpClient bean：
 * llmHttpClient（默认 @Component，对话用）、llmProbeHttpClient（本类，探测用），
 * 注入时必须用 @Qualifier 指名。
 */
@Configuration
public class ProviderHttpConfig {

    @Bean("llmProbeHttpClient")
    public LlmHttpClient llmProbeHttpClient(
            ObjectMapper objectMapper,
            @Value("${hify.http.probe.connect-timeout:5s}") Duration connectTimeout,
            @Value("${hify.http.probe.read-timeout:10s}") Duration readTimeout) {
        // stream-read 超时探测用不到，与 read 一致即可
        return new LlmHttpClient(objectMapper, connectTimeout, readTimeout, readTimeout);
    }
}
