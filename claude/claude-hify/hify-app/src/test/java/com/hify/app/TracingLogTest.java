package com.hify.app;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.chat.dto.ChatStreamResult;
import com.hify.chat.service.ChatService;
import com.hify.common.log.JsonLogLayout;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.jayway.jsonpath.JsonPath.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 结构化日志与 traceId 链路串联验证：
 * <ol>
 *   <li>同步请求：OpenTelemetry 生成的 traceId 进 MDC，请求线程全部日志共用，
 *       响应头 X-Trace-Id 可对日志，不同请求 traceId 不同</li>
 *   <li>JSON 布局：六个规范字段齐全，含引号/换行/中文的消息合法转义</li>
 *   <li>异步链路：对话流跑在 llmExecutor 上，traceId 透传过去，
 *       LLM 调用开始/结束、对话完成等关键节点日志都在同一链路里</li>
 *   <li>控制器入口：对话请求进入日志经 HTTP 触发，traceId 由 TracingFilter 写入</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TracingLogTest {

    private static final String FIXTURE_PREFIX = "trace测试-";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer stubServer;

    private static int stubPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatService chatService;

    private Logger rootLogger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void startStub() throws Exception {
        // openai 兼容流式 stub（无工具场景：两句 token + usage + DONE）
        stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubServer.createContext("/chat/completions", TracingLogTest::handleOpenAi);
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        stubServer.stop(0);
    }

    @BeforeEach
    void attachLogCapture() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        cleanupFixtures();
    }

    @AfterEach
    void detachLogCapture() {
        rootLogger.detachAppender(appender);
        cleanupFixtures();
    }

    @Test
    void 同步请求共享traceId() throws Exception {
        appender.list.clear();
        MvcResult first = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/providers"))
                .andExpect(status().isOk())
                .andReturn();
        String trace1 = first.getResponse().getHeader("X-Trace-Id");
        assertThat(trace1).isNotBlank().matches("[0-9a-f]{32}");

        // 请求线程上的日志全部带同一 traceId（无 traceId 的后台噪声事件被过滤）
        List<ILoggingEvent> ofTrace1 = eventsWithTraceId(trace1);
        assertThat(ofTrace1).isNotEmpty();
        assertThat(distinctTraceIds()).containsExactly(trace1);
        // 请求行日志在链路里
        assertThat(ofTrace1)
                .anyMatch(e -> e.getFormattedMessage().contains("GET /api/v1/providers"));

        // 第二个请求：traceId 不同
        appender.list.clear();
        MvcResult second = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/providers"))
                .andExpect(status().isOk())
                .andReturn();
        String trace2 = second.getResponse().getHeader("X-Trace-Id");
        assertThat(trace2).isNotBlank().matches("[0-9a-f]{32}").isNotEqualTo(trace1);
    }

    @Test
    void json布局字段与转义() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.start();

        LoggingEvent event = new LoggingEvent("fqcn", rootLogger, Level.INFO,
                "带\"引号\"、换行\n与中文消息", null, null);
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put("traceId", "0123456789abcdef0123456789abcdef");
        event.setMDCPropertyMap(mdc);
        JsonNode node = MAPPER.readTree(layout.doLayout(event));

        assertThat(node.get("timestamp")).isNotNull();
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("traceId").asText()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(node.get("thread")).isNotNull();
        assertThat(node.get("logger")).isNotNull();
        assertThat(node.get("message").asText()).isEqualTo("带\"引号\"、换行\n与中文消息");

        // 无 traceId 的日志：字段直接省略，不输出 null
        LoggingEvent noTrace = new LoggingEvent("fqcn", rootLogger, Level.WARN, "无trace", null, null);
        JsonNode node2 = MAPPER.readTree(layout.doLayout(noTrace));
        assertThat(node2.has("traceId")).isFalse();
        assertThat(node2.get("message").asText()).isEqualTo("无trace");
    }

    @Test
    void 异步链路traceId透传与关键节点日志() throws Exception {
        long providerId = createProvider("trace-stub-openai", "openai_compatible");
        long modelId = createModel(providerId, "trace-test-model");
        long agentId = createAgent(FIXTURE_PREFIX + "代理", modelId);
        long sessionId = createAgentSession(agentId);

        appender.list.clear();
        // 模拟 TracingFilter 在请求线程上写入 MDC，提交后由 MdcTaskDecorator 透传到 llmExecutor
        String marker = "11111111222222223333333344444444";
        MDC.put("traceId", marker);
        ChatStreamResult result;
        try {
            result = chatService.streamChat(sessionId, "你好", delta -> { })
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            MDC.remove("traceId");
        }
        assertThat(result.isOk()).isTrue();

        // llmExecutor 线程上的日志与请求线程共用同一 traceId
        List<ILoggingEvent> ofMarker = eventsWithTraceId(marker);
        assertThat(ofMarker).isNotEmpty();
        assertThat(ofMarker.stream().map(ILoggingEvent::getThreadName))
                .anyMatch(thread -> thread.startsWith("llm-"));
        // 关键节点：LLM 调用开始 / 结束 / 对话完成
        assertThat(ofMarker).anyMatch(e -> e.getFormattedMessage().contains("LLM 调用开始"));
        assertThat(ofMarker).anyMatch(e -> e.getFormattedMessage().contains("LLM 调用结束"));
        assertThat(ofMarker).anyMatch(e -> e.getFormattedMessage().contains("对话完成"));
    }

    @Test
    void 对话请求进入日志带filter的traceId() throws Exception {
        appender.list.clear();
        // 不存在的会话：入口日志照打，同步校验失败走全局异常处理回 Result JSON
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/sessions/999999/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        Set<String> ids = distinctTraceIds();
        assertThat(ids).hasSize(1);
        String traceId = ids.iterator().next();
        assertThat(eventsWithTraceId(traceId))
                .anyMatch(e -> e.getFormattedMessage().contains("对话请求进入 sessionId=999999"));
    }

    // ---------- 日志捕获工具 ----------

    private List<ILoggingEvent> eventsWithTraceId(String traceId) {
        return appender.list.stream()
                .filter(e -> traceId.equals(e.getMDCPropertyMap().get("traceId")))
                .collect(Collectors.toList());
    }

    private Set<String> distinctTraceIds() {
        return appender.list.stream()
                .map(e -> e.getMDCPropertyMap().get("traceId"))
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
    }

    // ---------- openai 兼容流式 stub ----------

    private static void handleOpenAi(HttpExchange exchange) throws java.io.IOException {
        List<String> frames = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好，\"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"我是测试助手\"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\n\n",
                "data: {\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}\n\n",
                "data: [DONE]\n\n");
        byte[] bytes = String.join("", frames).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------- 夹具 ----------

    private long createProvider(String name, String type) throws Exception {
        String resp = postJson("/api/v1/providers",
                "{\"name\":\"%s\",\"type\":\"%s\",\"baseUrl\":\"http://localhost:%d\",\"apiKey\":\"sk-trace-key\"}"
                        .formatted(name, type, stubPort));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createModel(long providerId, String modelName) throws Exception {
        String resp = postJson("/api/v1/model-configs",
                "{\"providerId\":%d,\"modelName\":\"%s\",\"modelType\":\"chat\",\"maxContextTokens\":32000}"
                        .formatted(providerId, modelName));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createAgent(String name, long modelId) throws Exception {
        String resp = postJson("/api/v1/agents", """
                {"name":"%s","description":"日志链路测试","modelConfigId":%d,
                 "systemPrompt":"你是测试助手","temperature":0.3,"maxTokens":0,"enabled":1}
                """.formatted(name, modelId));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createAgentSession(long agentId) throws Exception {
        String resp = postJson("/api/v1/sessions", "{\"agentId\":" + agentId + "}");
        return parse(resp).read("$.data.id", Long.class);
    }

    private String postJson(String url, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 只删测试夹具（精确前缀），用户真实数据不动 */
    private void cleanupFixtures() {
        List<Long> agentIds = jdbcTemplate.queryForList(
                "SELECT id FROM agent WHERE name LIKE '" + FIXTURE_PREFIX + "%'", Long.class);
        for (Long agentId : agentIds) {
            jdbcTemplate.update("DELETE FROM chat_message WHERE session_id IN "
                    + "(SELECT id FROM chat_session WHERE agent_id = " + agentId + ")");
            jdbcTemplate.update("DELETE FROM chat_session WHERE agent_id = " + agentId);
            jdbcTemplate.update("DELETE FROM agent WHERE id = " + agentId);
        }
        jdbcTemplate.execute("DELETE FROM model_config WHERE model_name LIKE 'trace-test-%'");
        jdbcTemplate.execute("DELETE FROM provider WHERE name LIKE 'trace-stub-%'");
    }
}
