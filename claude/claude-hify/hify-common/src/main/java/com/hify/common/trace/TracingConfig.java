package com.hify.common.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * OpenTelemetry 基础设施。
 *
 * <p>一期目标只是<b>生成 traceId 串联日志</b>：SdkTracerProvider 不挂任何
 * SpanProcessor（span 只在本地生成 id，不导出），TracingFilter 每个请求起一个
 * SERVER span，traceId 写入 MDC 随日志输出。
 *
 * <p>扩展点：后续接 Jaeger / Zipkin 时，给 {@link SdkTracerProvider} 加
 * BatchSpanProcessor + OtlpGrpcSpanExporter 即可，业务代码（Tracer / Span 用法）不用动。
 * 上游传播也已就绪：请求带 W3C traceparent 头时，span 会挂在传入的 trace 上。
 */
@Configuration
public class TracingConfig {

    /** MDC 键名，与 logback 配置里的 %X{traceId} 对应 */
    public static final String TRACE_ID_KEY = "traceId";

    @Bean(destroyMethod = "close")
    public SdkTracerProvider sdkTracerProvider(
            @Value("${spring.application.name:hify}") String serviceName) {
        return SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), serviceName))))
                .build();
    }

    @Bean
    public OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
        // 不注册全局单例（buildAndRegisterGlobal），避免多 Spring 上下文（测试）互相覆盖
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Bean
    public Tracer hifyTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("hify");
    }

    /**
     * 追踪过滤器注册为最高优先级、覆盖 REQUEST 与 ASYNC 两种派发
     * （SSE 异步完成后的 ASYNC 派发需要它恢复 MDC 并收尾 span）。
     */
    @Bean
    public FilterRegistrationBean<TracingFilter> tracingFilter(Tracer tracer) {
        FilterRegistrationBean<TracingFilter> registration =
                new FilterRegistrationBean<>(new TracingFilter(tracer));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.addUrlPatterns("/*");
        registration.setName("tracingFilter");
        return registration;
    }
}
