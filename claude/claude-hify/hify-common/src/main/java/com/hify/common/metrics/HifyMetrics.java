package com.hify.common.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * Hify 业务指标统一入口（Micrometer，Prometheus 命名渲染后统一 hify_ 前缀）：
 * <ul>
 *   <li>hify.chat.requests{agent, outcome} + hify.chat.request.duration——对话请求</li>
 *   <li>hify.llm.calls{provider, model, outcome} + hify.llm.call.duration{provider, model}——LLM 调用</li>
 *   <li>hify.circuit.breaker.state{breaker, state}——熔断器状态（当前态为 1，其余为 0）</li>
 *   <li>hify.mcp.tool.calls{server, tool, outcome}——MCP 工具调用</li>
 * </ul>
 * 指标经 /actuator/prometheus 暴露。Micrometer 的 counter()/timer() 按 Id 幂等，
 * 重复获取返回同一实例，无需自行缓存。
 */
@Component
public class HifyMetrics {

    private final MeterRegistry registry;

    /** 已注册过状态 gauge 的熔断器名（防重复注册） */
    private final Set<String> registeredBreakers = ConcurrentHashMap.newKeySet();

    public HifyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 对话请求完成。agent 标签：Agent 会话取 agentId，工作流会话固定 "workflow"。
     */
    public void recordChatRequest(String agent, long latencyMs, boolean ok) {
        Counter.builder("hify.chat.requests")
                .tag("agent", agent)
                .tag("outcome", ok ? "success" : "failure")
                .register(registry)
                .increment();
        Timer.builder("hify.chat.request.duration")
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 一次 LLM 调用结束。outcome：success / failure（含熔断快速失败）/ aborted（客户端断流，非 Provider 故障）。
     */
    public void recordLlmCall(String provider, String model, long latencyMs, String outcome) {
        Counter.builder("hify.llm.calls")
                .tag("provider", provider)
                .tag("model", model)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("hify.llm.call.duration")
                .tag("provider", provider)
                .tag("model", model)
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /** 一次 MCP 工具调用结束 */
    public void recordMcpToolCall(String server, String tool, boolean ok) {
        Counter.builder("hify.mcp.tool.calls")
                .tag("server", server)
                .tag("tool", tool)
                .tag("outcome", ok ? "success" : "failure")
                .register(registry)
                .increment();
    }

    /**
     * 注册熔断器状态 gauge：每个熔断器 × 三种状态各一条序列，当前态值为 1、其余为 0
     * （Prometheus 常规做法，告警规则可对 state="OPEN" 直接判断）。
     * 熔断器实例由 CircuitBreakerRegistry 强引用持有，gauge 弱引用不会失效。
     */
    public void registerCircuitBreaker(String name, CircuitBreaker breaker) {
        if (!registeredBreakers.add(name)) {
            return;
        }
        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            io.micrometer.core.instrument.Gauge
                    .builder("hify.circuit.breaker.state",
                            breaker, b -> b.getState() == state ? 1.0 : 0.0)
                    .tag("breaker", name)
                    .tag("state", state.name())
                    .register(registry);
        }
    }
}
