package com.hify.common.resilience;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonErrorCode;
import com.hify.common.exception.ErrorCode;
import com.hify.common.http.LlmApiException;
import com.hify.common.http.LlmApiException.Kind;
import com.hify.common.metrics.HifyMetrics;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * LLM 调用的熔断 + 重试统一外壳。
 *
 * <p>熔断：每个 Provider 一个独立 {@link CircuitBreaker} 实例（按名懒创建，共用
 * resilience4j default 配置，见 application.yml 的 resilience4j.circuitbreaker 段）。
 * 一个 Provider 挂了只熔断它自己，不影响其他 Provider。
 *
 * <p>重试（按 {@link Kind} 分类，CLAUDE.md：网络抖动重试、认证失败不重试、限流退避重试）：
 * <ul>
 *   <li>TIMEOUT：固定间隔重试（默认 1s × 2 次）</li>
 *   <li>RATE_LIMITED：指数退避重试（默认 2s、4s）</li>
 *   <li>AUTH_FAILED / CALL_FAILED：不重试，直接抛出</li>
 * </ul>
 * 重试的每次尝试都经过熔断器（每次失败都会计入失败率）。
 *
 * <p>调用方应运行在 llmExecutor 线程池上：重试退避是阻塞 sleep。
 * 熔断开启时抛 {@link BizException}(SERVICE_UNAVAILABLE)，调用失败抛 {@link LlmApiException}。
 */
@Slf4j
@Service
public class CircuitBreakerService {

    private final CircuitBreakerRegistry registry;

    private final int timeoutRetries;

    private final Duration timeoutInterval;

    private final int rateLimitRetries;

    private final Duration rateLimitBaseDelay;

    private final HifyMetrics hifyMetrics;

    public CircuitBreakerService(
            CircuitBreakerRegistry registry,
            HifyMetrics hifyMetrics,
            @Value("${hify.resilience.retry.timeout-retries:2}") int timeoutRetries,
            @Value("${hify.resilience.retry.timeout-interval:1s}") Duration timeoutInterval,
            @Value("${hify.resilience.retry.rate-limit-retries:2}") int rateLimitRetries,
            @Value("${hify.resilience.retry.rate-limit-base-delay:2s}") Duration rateLimitBaseDelay) {
        this.registry = registry;
        this.hifyMetrics = hifyMetrics;
        this.timeoutRetries = timeoutRetries;
        this.timeoutInterval = timeoutInterval;
        this.rateLimitRetries = rateLimitRetries;
        this.rateLimitBaseDelay = rateLimitBaseDelay;
    }

    /** 按提供商名获取或创建独立熔断器实例（同名返回同一实例），首次创建时挂状态指标 */
    public CircuitBreaker get(String providerName) {
        CircuitBreaker breaker = registry.circuitBreaker(providerName);
        hifyMetrics.registerCircuitBreaker(providerName, breaker);
        return breaker;
    }

    /**
     * 熔断 + 重试的执行外壳。
     *
     * @param providerName 提供商名（熔断器粒度）
     * @param call         实际的 LLM 调用，异常应统一抛 {@link LlmApiException}
     */
    public <T> T execute(String providerName, Supplier<T> call) {
        return execute(providerName, call, () -> true);
    }

    /**
     * 熔断 + 重试的执行外壳（流式调用用）：重试前先问 allowRetry——
     * 已经向客户端推送过 token 的流中断不允许重试（从头重发会导致内容重复），
     * 此类失败只计熔断失败率、不重试。allowRetry 为 false 时直接抛出原始异常。
     */
    public <T> T execute(String providerName, Supplier<T> call, BooleanSupplier allowRetry) {
        CircuitBreaker breaker = get(providerName);
        int timeoutAttempts = 0;
        int rateLimitAttempts = 0;
        while (true) {
            try {
                return breaker.executeSupplier(call);
            } catch (CallNotPermittedException e) {
                // 熔断开启：快速失败，不再重试
                throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                        "Provider 熔断已开启，快速失败：" + providerName);
            } catch (LlmApiException e) {
                if (!allowRetry.getAsBoolean()) {
                    throw e;
                }
                switch (e.getKind()) {
                    case TIMEOUT -> {
                        if (timeoutAttempts < timeoutRetries) {
                            timeoutAttempts++;
                            log.warn("LLM调用超时，固定间隔重试第{}次 provider={}",
                                    timeoutAttempts, providerName);
                            sleep(timeoutInterval.toMillis(), e);
                            continue;
                        }
                    }
                    case RATE_LIMITED -> {
                        if (rateLimitAttempts < rateLimitRetries) {
                            // 指数退避：base × 2^n，默认 2s、4s
                            long delay = rateLimitBaseDelay.toMillis() << rateLimitAttempts;
                            rateLimitAttempts++;
                            log.warn("LLM调用被限流，退避{}ms后重试第{}次 provider={}",
                                    delay, rateLimitAttempts, providerName);
                            sleep(delay, e);
                            continue;
                        }
                    }
                    default -> { /* AUTH_FAILED、CALL_FAILED 不重试 */ }
                }
                throw e;
            }
        }
    }

    /** 退避 sleep；被中断时放弃重试，抛出原始异常 */
    private static void sleep(long millis, LlmApiException original) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw original;
        }
    }
}
