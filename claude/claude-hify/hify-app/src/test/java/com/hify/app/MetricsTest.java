package com.hify.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.chat.dto.ChatStreamResult;
import com.hify.chat.service.ChatService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.jayway.jsonpath.JsonPath.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prometheus 指标埋点验证（/actuator/prometheus）：
 * <ul>
 *   <li>工具对话全链路：对话请求数/延迟（按 agent 分组）、LLM 调用数/延迟
 *       （按 provider、model 分组，含两轮工具循环）、MCP 工具调用数、熔断器状态 gauge</li>
 *   <li>失败路径：死端口 Provider → 对话与 LLM 调用记 failure</li>
 *   <li>抓取端点：指标名渲染为 hify_ 前缀的 Prometheus 文本格式</li>
 * </ul>
 *
 * <p>断言全部用基线+增量：MeterRegistry 是进程级累加值，不能断言绝对数。
 * 测试上下文默认禁用指标导出（生产运行不受影响）；显式设
 * management.prometheus.metrics.export.enabled=true 才能拿到
 * PrometheusMeterRegistry 与抓取端点（defaults 级属性会被测试禁用项压住，须用注册器专属属性）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.prometheus.metrics.export.enabled=true")
@AutoConfigureMockMvc
class MetricsTest {

    private static final String FIXTURE_PREFIX = "metrics测试-";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer stubServer;

    private static int stubPort;

    @Autowired
    private MockMvc mockMvc;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatService chatService;

    @Autowired
    private MeterRegistry registry;

    @BeforeAll
    static void startStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubServer.createContext("/mcp", MetricsTest::handleMcp);
        stubServer.createContext("/chat/completions", MetricsTest::handleOpenAi);
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        stubServer.stop(0);
    }

    @BeforeEach
    void setup() {
        cleanupFixtures();
    }

    @AfterEach
    void cleanup() {
        cleanupFixtures();
    }

    @Test
    void 工具对话全链路指标() throws Exception {
        long providerId = createProvider("metrics-stub-openai");
        long modelId = createModel(providerId, "metrics-test-model");
        long agentId = createAgent(FIXTURE_PREFIX + "代理", modelId);
        long serverId = createMcpServer(FIXTURE_PREFIX + "服务");
        putJson("/api/v1/agents/" + agentId + "/tools",
                "{\"bindings\":[{\"mcpServerId\":" + serverId + "}]}");
        long sessionId = createAgentSession(agentId);

        // 基线
        double chatOk0 = count("hify.chat.requests", "agent", String.valueOf(agentId), "outcome", "success");
        double chatFail0 = count("hify.chat.requests", "agent", String.valueOf(agentId), "outcome", "failure");
        double chatTimer0 = timerCount("hify.chat.request.duration");
        double llmOk0 = count("hify.llm.calls", "provider", "metrics-stub-openai",
                "model", "metrics-test-model", "outcome", "success");
        double llmFail0 = count("hify.llm.calls", "provider", "metrics-stub-openai",
                "model", "metrics-test-model", "outcome", "failure");
        double llmTimer0 = timerCount("hify.llm.call.duration",
                "provider", "metrics-stub-openai", "model", "metrics-test-model");
        double toolOk0 = count("hify.mcp.tool.calls", "server", FIXTURE_PREFIX + "服务",
                "tool", "echo", "outcome", "success");

        ChatStreamResult result = chatService
                .streamChat(sessionId, "帮我查一下", delta -> { })
                .get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result.isOk()).isTrue();
        // whenComplete 回调与 get() 的唤醒有竞态，短暂等待计数落地
        awaitTrue(() -> count("hify.chat.requests", "agent", String.valueOf(agentId),
                "outcome", "success") > chatOk0, 3000);

        // 对话请求：成功计数 +1，延迟分布（Timer）+1
        assertThat(count("hify.chat.requests", "agent", String.valueOf(agentId), "outcome", "success"))
                .isEqualTo(chatOk0 + 1);
        assertThat(count("hify.chat.requests", "agent", String.valueOf(agentId), "outcome", "failure"))
                .isEqualTo(chatFail0);
        assertThat(timerCount("hify.chat.request.duration")).isEqualTo(chatTimer0 + 1);

        // LLM 调用：两轮（工具决策轮 + 收尾轮）各计一次成功，延迟 Timer +2
        assertThat(count("hify.llm.calls", "provider", "metrics-stub-openai",
                "model", "metrics-test-model", "outcome", "success")).isEqualTo(llmOk0 + 2);
        assertThat(count("hify.llm.calls", "provider", "metrics-stub-openai",
                "model", "metrics-test-model", "outcome", "failure")).isEqualTo(llmFail0);
        assertThat(timerCount("hify.llm.call.duration",
                "provider", "metrics-stub-openai", "model", "metrics-test-model"))
                .isEqualTo(llmTimer0 + 2);

        // MCP 工具调用：成功 +1
        assertThat(count("hify.mcp.tool.calls", "server", FIXTURE_PREFIX + "服务",
                "tool", "echo", "outcome", "success")).isEqualTo(toolOk0 + 1);

        // 熔断器状态 gauge：该请求触达的 provider / mcp 两个熔断器均为 CLOSED=1（OPEN/HALF_OPEN=0）
        assertThat(gaugeValue("provider:" + providerId, "CLOSED")).isEqualTo(1.0);
        assertThat(gaugeValue("provider:" + providerId, "OPEN")).isZero();
        assertThat(gaugeValue("mcp:" + serverId, "CLOSED")).isEqualTo(1.0);

        // 抓取端点：hify_ 前缀 Prometheus 文本格式（真实端口走 Tomcat，与生产抓取同路径）
        String scrape = java.net.http.HttpClient.newHttpClient()
                .send(java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create("http://localhost:" + serverPort + "/actuator/prometheus"))
                                .GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                .body();
        assertThat(scrape).contains("hify_chat_requests_total");
        assertThat(scrape).contains("hify_llm_calls_total");
        assertThat(scrape).contains("hify_mcp_tool_calls_total");
        assertThat(scrape).contains("hify_circuit_breaker_state");
        assertThat(scrape).contains("hify_chat_request_duration_seconds_bucket");
    }

    @Test
    void 失败路径计failure() throws Exception {
        // 死端口（连接立刻拒绝，无重试）：Provider 建完即用
        long providerId = createProviderRaw("metrics-stub-dead", "http://localhost:9");
        long modelId = createModel(providerId, "metrics-test-dead");
        long agentId = createAgent(FIXTURE_PREFIX + "死代理", modelId);
        long sessionId = createAgentSession(agentId);

        double chatFail0 = count("hify.chat.requests", "agent", String.valueOf(agentId), "outcome", "failure");
        double llmFail0 = count("hify.llm.calls", "provider", "metrics-stub-dead",
                "model", "metrics-test-dead", "outcome", "failure");

        ChatStreamResult result = chatService
                .streamChat(sessionId, "你好", delta -> { })
                .get(20, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result.isOk()).isFalse();
        awaitTrue(() -> count("hify.chat.requests", "agent", String.valueOf(agentId),
                "outcome", "failure") > chatFail0, 3000);
        assertThat(count("hify.chat.requests", "agent", String.valueOf(agentId), "outcome", "failure"))
                .isEqualTo(chatFail0 + 1);
        assertThat(count("hify.llm.calls", "provider", "metrics-stub-dead",
                "model", "metrics-test-dead", "outcome", "failure")).isEqualTo(llmFail0 + 1);
        // 失败也是一次调用，延迟照记
        assertThat(timerCount("hify.llm.call.duration",
                "provider", "metrics-stub-dead", "model", "metrics-test-dead"))
                .isGreaterThanOrEqualTo(1);
    }

    // ---------- 指标读取工具 ----------

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 轮询等待条件成立（whenComplete 指标落地的竞态兜底） */
    private static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private double timerCount(String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0.0 : timer.count();
    }

    private double gaugeValue(String breaker, String state) {
        return registry.find("hify.circuit.breaker.state")
                .tag("breaker", breaker).tag("state", state).gauge().value();
    }

    // ---------- stub ----------

    private static void handleMcp(HttpExchange exchange) throws IOException {
        JsonNode req = MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String method = req.path("method").asText("");
        long id = req.path("id").asLong(0);
        switch (method) {
            case "initialize" -> {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "stub-session-1");
                respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                        + "\"serverInfo\":{\"name\":\"stub\",\"version\":\"0\"}}}");
            }
            case "notifications/initialized" -> exchange.sendResponseHeaders(202, -1);
            case "tools/list" -> respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"回声工具\"}]}}");
            case "tools/call" -> respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"回声:ok\"}]}}");
            default -> respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"error\":{\"code\":-32601,\"message\":\"unknown method\"}}");
        }
        exchange.close();
    }

    private static void handleOpenAi(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<String> frames = new ArrayList<>();
        if (body.contains("\"role\":\"tool\"")) {
            frames.add(frame(Map.of("content", "工具说：回声:ok")));
            frames.add(frame(Map.of()));
        } else if (body.contains("\"tools\":[")) {
            // openai 工具定义的 name 在 tools[0].function.name
            String toolName = "echo";
            try {
                JsonNode tool = MAPPER.readTree(body).path("tools").path(0);
                toolName = tool.path("function").path("name").asText(tool.path("name").asText("echo"));
            } catch (Exception ignored) { }
            frames.add(frame(Map.of("content", "让我查一下\n")));
            frames.add(frame(Map.of("tool_calls", List.of(Map.of(
                    "index", 0, "id", "call-metrics-1", "type", "function",
                    "function", Map.of("name", toolName, "arguments", "{\"query\":\"测试\"}"))))));
            frames.add(frame(Map.of()));
        } else {
            frames.add(frame(Map.of("content", "默认回复")));
            frames.add(frame(Map.of()));
        }
        frames.add("data: {\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}\n\n");
        frames.add("data: [DONE]\n\n");
        respondSse(exchange, String.join("", frames));
        exchange.close();
    }

    private static String frame(Map<String, Object> delta) {
        try {
            return "data: " + MAPPER.writeValueAsString(Map.of("choices",
                    List.of(Map.of("index", 0, "delta", delta)))) + "\n\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondSse(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------- 夹具 ----------

    private long createProvider(String name) throws Exception {
        return createProviderRaw(name, "http://localhost:" + stubPort);
    }

    private long createProviderRaw(String name, String baseUrl) throws Exception {
        String resp = postJson("/api/v1/providers",
                "{\"name\":\"%s\",\"type\":\"openai_compatible\",\"baseUrl\":\"%s\",\"apiKey\":\"sk-metrics-key\"}"
                        .formatted(name, baseUrl));
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
                {"name":"%s","description":"指标测试","modelConfigId":%d,
                 "systemPrompt":"你是测试助手","temperature":0.3,"maxTokens":0,"enabled":1}
                """.formatted(name, modelId));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createMcpServer(String name) throws Exception {
        String resp = postJson("/api/v1/mcp-servers", """
                {"name":"%s","description":"指标 stub","transport":"http",
                 "url":"http://localhost:%d/mcp","enabled":1}
                """.formatted(name, stubPort));
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

    private String putJson(String url, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put(url)
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
            jdbcTemplate.update("DELETE FROM agent_tool WHERE agent_id = " + agentId);
            jdbcTemplate.update("DELETE FROM agent_knowledge WHERE agent_id = " + agentId);
            jdbcTemplate.update("DELETE FROM agent WHERE id = " + agentId);
        }
        jdbcTemplate.execute("DELETE FROM agent_tool WHERE mcp_server_id IN "
                + "(SELECT id FROM mcp_server WHERE name LIKE '" + FIXTURE_PREFIX + "%')");
        jdbcTemplate.execute("DELETE FROM mcp_server WHERE name LIKE '" + FIXTURE_PREFIX + "%'");
        jdbcTemplate.execute("DELETE FROM model_config WHERE model_name LIKE 'metrics-test-%'");
        jdbcTemplate.execute("DELETE FROM provider WHERE name LIKE 'metrics-stub-%'");
    }
}
