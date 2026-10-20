package com.hify.common.log;

import java.time.Duration;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * 请求日志拦截器：请求完成后记录 method / path / status / 耗时，超过阈值（默认 1s）标 WARN。
 *
 * <p>traceId 的生成与 MDC 生命周期由 {@link com.hify.common.trace.TracingFilter} 负责
 * （OpenTelemetry，覆盖 ASYNC 派发）；本拦截器只管计时和请求行日志，
 * 输出时 MDC 里的 traceId 自动随日志带上。
 *
 * <p>SSE 等异步请求的 async 派发不重置计时，最终耗时按 INFO 记录
 * （流式对话天然超过阈值，WARN 会刷屏，慢请求告警只针对普通同步接口）。
 */
@Slf4j
@Component
public class RequestLogInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = RequestLogInterceptor.class.getName() + ".start";

    private final long slowThresholdMs;

    public RequestLogInterceptor(
            @Value("${hify.log.slow-request-threshold:1s}") Duration slowThreshold) {
        this.slowThresholdMs = slowThreshold.toMillis();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;   // ASYNC 派发：不重置计时（traceId 由 TracingFilter 恢复）
        }
        if (request.getRequestURI().startsWith("/actuator/")) {
            return false;   // 监控采集端点不记请求日志（Prometheus 周期抓取会刷屏）
        }
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, @Nullable Exception ex) {
        Long start = (Long) request.getAttribute(START_ATTR);
        try {
            if (start != null) {
                long cost = System.currentTimeMillis() - start;
                String line = "%s %s %d %dms".formatted(
                        request.getMethod(), request.getRequestURI(), response.getStatus(), cost);
                boolean async = request.getDispatcherType() == DispatcherType.ASYNC;
                if (cost > slowThresholdMs && !async) {
                    log.warn("SLOW {}", line);
                } else {
                    log.info(line);
                }
            }
        } finally {
            request.removeAttribute(START_ATTR);
        }
    }
}
