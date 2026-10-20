package com.hify.common.trace;

import java.io.IOException;
import java.util.Collections;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 请求追踪过滤器：每个 HTTP 请求创建一个 SERVER span，并把 traceId 放进 MDC，
 * 该请求线程上的所有日志即共享同一个 traceId。
 *
 * <p>生命周期覆盖 SSE 异步请求：
 * <ul>
 *   <li>REQUEST 派发：创建 span + MDC；若链路内开启了异步（SseEmitter），
 *       span 暂不结束，交给 ASYNC 派发收尾</li>
 *   <li>ASYNC 派发（异步完成回来）：从请求属性取回 span，恢复 MDC 让请求完成日志
 *       也带 traceId，链路结束后 end span 并清理 MDC</li>
 * </ul>
 *
 * <p>上游传播：请求带 W3C traceparent 头时 span 挂到传入 trace（网关 / 前置服务串联预留）。
 * traceId 同时回写响应头 X-Trace-Id，方便排障时对日志。
 */
public class TracingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TracingFilter.class);

    private static final String SPAN_ATTR = TracingFilter.class.getName() + ".span";

    /** traceparent 头提取器（W3C Trace Context） */
    private static final TextMapGetter<HttpServletRequest> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    private final Tracer tracer;

    public TracingFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        Object attr = httpRequest.getAttribute(SPAN_ATTR);
        if (attr instanceof Span span) {
            // ASYNC 派发（异步请求完成）：恢复 MDC，链路结束后收尾
            MDC.put(TracingConfig.TRACE_ID_KEY, span.getSpanContext().getTraceId());
            try {
                chain.doFilter(request, response);
            } finally {
                span.end();
                MDC.remove(TracingConfig.TRACE_ID_KEY);
            }
            return;
        }

        if (httpRequest.getDispatcherType() != jakarta.servlet.DispatcherType.REQUEST) {
            // ERROR 派发等：无 span 可恢复，直接放行
            chain.doFilter(request, response);
            return;
        }

        Context parent = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), httpRequest, HEADER_GETTER);
        Span span = tracer.spanBuilder("HTTP " + httpRequest.getMethod() + " "
                        + httpRequest.getRequestURI())
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .startSpan();
        String traceId = span.getSpanContext().getTraceId();
        httpRequest.setAttribute(SPAN_ATTR, span);
        httpResponse.setHeader("X-Trace-Id", traceId);
        MDC.put(TracingConfig.TRACE_ID_KEY, traceId);
        try (Scope ignored = span.makeCurrent()) {
            chain.doFilter(request, response);
        } finally {
            if (httpRequest.isAsyncStarted()) {
                // SSE：容器稍后发起 ASYNC 派发，span 与 MDC 由那边收尾
                log.debug("异步请求进入追踪挂起 uri={}", httpRequest.getRequestURI());
            } else {
                span.end();
                MDC.remove(TracingConfig.TRACE_ID_KEY);
            }
        }
    }
}
