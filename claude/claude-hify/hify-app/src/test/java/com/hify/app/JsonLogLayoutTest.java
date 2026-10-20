package com.hify.app;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.log.JsonLogLayout;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonLogLayout 测试：输出必须是合法 JSON（Jackson 解析回环验证），
 * 特殊字符正确转义，traceId / stacktrace 字段正确。
 */
class JsonLogLayoutTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final JsonLogLayout layout = new JsonLogLayout();

    private final Logger logger = (Logger) LoggerFactory.getLogger("json.layout.test");

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    JsonLogLayoutTest() {
        layout.start();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private ILoggingEvent lastEvent() {
        List<ILoggingEvent> events = appender.list;
        return events.get(events.size() - 1);
    }

    @Test
    void 基本字段与特殊字符转义() throws Exception {
        String trickyMessage = "消息含\"引号\"和\\反斜杠\n换行\t制表";

        logger.info(trickyMessage);

        JsonNode node = objectMapper.readTree(layout.doLayout(lastEvent()));

        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("thread").asText()).isEqualTo(Thread.currentThread().getName());
        assertThat(node.get("logger").asText()).isEqualTo("json.layout.test");
        assertThat(node.get("timestamp").asText()).isNotBlank();
        // 回环相等 = 转义正确
        assertThat(node.get("message").asText()).isEqualTo(trickyMessage);
        assertThat(node.has("stack")).isFalse();
        assertThat(node.has("traceId")).isFalse();
    }

    @Test
    void traceId来自MDC() throws Exception {
        MDC.put("traceId", "abc123");

        logger.info("带traceId的消息");

        JsonNode node = objectMapper.readTree(layout.doLayout(lastEvent()));
        assertThat(node.get("traceId").asText()).isEqualTo("abc123");
    }

    @Test
    void 异常堆栈转为stack字段() throws Exception {
        logger.error("出错了", new IllegalStateException("boom"));

        JsonNode node = objectMapper.readTree(layout.doLayout(lastEvent()));
        assertThat(node.get("stack").asText()).contains("IllegalStateException", "boom");
        // 每行一个 JSON：首行以 { 开始，恰好一个换行符在结尾
        String json = layout.doLayout(lastEvent());
        assertThat(json.indexOf('{')).isZero();
        assertThat(json.chars().filter(c -> c == '\n').count()).isEqualTo(1);
    }
}
