package com.hify.app;

import java.time.Duration;
import java.util.List;

import com.hify.common.log.RequestLogInterceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequestLogInterceptor 测试：直接驱动拦截器（不依赖 MVC 上下文），
 * 慢阈值压到 30ms 验证分级、traceId 生命周期、ASYNC 派发跳过计时。
 */
class RequestLogInterceptorTest {

    private static final Object HANDLER = new Object();

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLogInterceptor.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        appender.list.clear();   // 同一实例跨用例复用，先清空
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setDispatcherType(DispatcherType.REQUEST);
        return request;
    }

    @Test
    void 快请求记录INFO日志含method_path_status_耗时() {
        RequestLogInterceptor interceptor = new RequestLogInterceptor(Duration.ofMillis(30));
        MockHttpServletRequest req = request("GET", "/api/v1/demo-items");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(200);

        interceptor.preHandle(req, resp, HANDLER);
        interceptor.afterCompletion(req, resp, HANDLER, null);

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("GET", "/api/v1/demo-items", "200")
                .contains("ms");
    }

    @Test
    void 慢请求标WARN() throws InterruptedException {
        RequestLogInterceptor interceptor = new RequestLogInterceptor(Duration.ofMillis(30));
        MockHttpServletRequest req = request("POST", "/api/v1/chat");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(500);

        interceptor.preHandle(req, resp, HANDLER);
        Thread.sleep(60);
        interceptor.afterCompletion(req, resp, HANDLER, null);

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).startsWith("SLOW POST /api/v1/chat 500");
    }

    @Test
    void 拦截器不管理traceId_由TracingFilter负责() {
        RequestLogInterceptor interceptor = new RequestLogInterceptor(Duration.ofMillis(30));
        MockHttpServletRequest req = request("GET", "/x");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        // 模拟 TracingFilter 先写入的 traceId：拦截器不得覆盖、也不得在完成时清理
        // （清理发生在 TracingFilter 的 finally，晚于拦截器的 afterCompletion）
        MDC.put("traceId", "filter写入的trace");
        interceptor.preHandle(req, resp, HANDLER);
        assertThat(MDC.get("traceId")).isEqualTo("filter写入的trace");

        // 请求处理期间，日志沿用 filter 写入的 traceId
        assertThat(appender.list).isEmpty();
        logger.info("请求处理中");
        assertThat(appender.list.get(0).getMDCPropertyMap().get("traceId")).isEqualTo("filter写入的trace");

        interceptor.afterCompletion(req, resp, HANDLER, null);
        assertThat(MDC.get("traceId")).isEqualTo("filter写入的trace");
    }

    @Test
    void ASYNC派发不重复计时() {
        RequestLogInterceptor interceptor = new RequestLogInterceptor(Duration.ofMillis(30));

        // ASYNC 派发的 preHandle：不生成 traceId
        MockHttpServletRequest asyncReq = new MockHttpServletRequest("GET", "/sse");
        asyncReq.setDispatcherType(DispatcherType.ASYNC);
        interceptor.preHandle(asyncReq, new MockHttpServletResponse(), HANDLER);
        assertThat(MDC.get("traceId")).isNull();
        // 无 start 属性（未经过 REQUEST 阶段）：afterCompletion 不记日志
        interceptor.afterCompletion(asyncReq, new MockHttpServletResponse(), HANDLER, null);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void SSE总耗时不标WARN() {
        RequestLogInterceptor interceptor = new RequestLogInterceptor(Duration.ofMillis(1));
        // REQUEST 阶段进入
        MockHttpServletRequest req = request("POST", "/api/v1/chat/stream");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(200);
        interceptor.preHandle(req, resp, HANDLER);
        // 模拟异步完成：async 派发阶段的 afterCompletion（耗时远超阈值）
        req.setDispatcherType(DispatcherType.ASYNC);
        interceptor.afterCompletion(req, resp, HANDLER, null);

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);   // 异步不告警，防 SSE 刷屏
        assertThat(event.getFormattedMessage()).contains("/api/v1/chat/stream");
    }
}
