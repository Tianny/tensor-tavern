package com.hify.app;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonErrorCode;
import com.hify.common.exception.ErrorCode;
import com.hify.common.http.LlmApiException;
import com.hify.common.http.LlmApiException.Kind;
import com.hify.common.metrics.HifyMetrics;
import com.hify.common.resilience.CircuitBreakerService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 熔断 + 重试测试。yml 配置断言走 Spring 上下文；
 * 重试 / 熔断行为用短间隔手工实例验证（不真实 sleep 秒级）。
 */
@SpringBootTest
class CircuitBreakerServiceTest {

    @Autowired
    private CircuitBreakerService service;

    @Test
    void 熔断器配置来自yml() {
        CircuitBreakerConfig config = service.get("openai").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        // 2.x 用 IntervalFunction 表达 open 状态等待时长；固定值时 apply 返回该毫秒数（attempt 从 1 起）
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
    }

    @Test
    void 按providerName获取或创建独立实例() {
        assertThat(service.get("openai")).isSameAs(service.get("openai"));
        assertThat(service.get("openai")).isNotSameAs(service.get("claude"));
    }

    // ---------- 重试行为（短间隔手工实例） ----------

    /** 重试间隔压到 10ms / 20ms，行为语义不变 */
    private CircuitBreakerService fastService(CircuitBreakerRegistry registry) {
        return new CircuitBreakerService(registry,
                new HifyMetrics(new SimpleMeterRegistry()),
                2, Duration.ofMillis(10), 2, Duration.ofMillis(20));
    }

    private static LlmApiException llmError(Kind kind) {
        return new LlmApiException(kind, "http://llm-test", 500, "测试异常");
    }

    @Test
    void 超时重试两次后成功() {
        AtomicInteger attempts = new AtomicInteger();

        String result = fastService(CircuitBreakerRegistry.ofDefaults())
                .execute("p", () -> {
                    if (attempts.incrementAndGet() <= 2) {
                        throw llmError(Kind.TIMEOUT);
                    }
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);   // 首次 + 2 次重试
    }

    @Test
    void 超时重试两次仍失败则抛出原异常() {
        AtomicInteger attempts = new AtomicInteger();

        LlmApiException e = catchThrowableOfType(() -> fastService(CircuitBreakerRegistry.ofDefaults())
                .execute("p", () -> {
                    attempts.incrementAndGet();
                    throw llmError(Kind.TIMEOUT);
                }), LlmApiException.class);

        assertThat(e.getKind()).isEqualTo(Kind.TIMEOUT);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void 认证失败不重试() {
        AtomicInteger attempts = new AtomicInteger();

        LlmApiException e = catchThrowableOfType(() -> fastService(CircuitBreakerRegistry.ofDefaults())
                .execute("p", () -> {
                    attempts.incrementAndGet();
                    throw llmError(Kind.AUTH_FAILED);
                }), LlmApiException.class);

        assertThat(e.getKind()).isEqualTo(Kind.AUTH_FAILED);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void 限流退避重试两次后仍失败抛出() {
        AtomicInteger attempts = new AtomicInteger();

        LlmApiException e = catchThrowableOfType(() -> fastService(CircuitBreakerRegistry.ofDefaults())
                .execute("p", () -> {
                    attempts.incrementAndGet();
                    throw llmError(Kind.RATE_LIMITED);
                }), LlmApiException.class);

        assertThat(e.getKind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(attempts.get()).isEqualTo(3);   // 退避 20ms、40ms 共两次
    }

    @Test
    void 熔断开启后快速失败_不影响其他provider() {
        // 小窗口配置：2 次失败即开（waitDuration 30s 保持 OPEN 状态）
        CircuitBreakerRegistry tiny = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        CircuitBreakerService fast = fastService(tiny);

        // CALL_FAILED 不重试；连续失败 2 次把熔断器打开
        for (int i = 0; i < 2; i++) {
            catchThrowableOfType(() -> fast.execute("p", () -> {
                throw llmError(Kind.CALL_FAILED);
            }), LlmApiException.class);
        }
        assertThat(tiny.circuitBreaker("p").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 熔断开启：调用快速失败，供应商函数根本不执行
        AtomicInteger attempts = new AtomicInteger();
        BizException e = catchThrowableOfType(() -> fast.execute("p", () -> {
            attempts.incrementAndGet();
            return "x";
        }), BizException.class);

        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThat(attempts.get()).isZero();

        // 其他 provider 的熔断器不受影响
        assertThat(tiny.circuitBreaker("q").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
