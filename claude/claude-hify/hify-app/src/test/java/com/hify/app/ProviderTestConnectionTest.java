package com.hify.app;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.jayway.jsonpath.JsonPath.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 连通性测试端到端：MockMvc → Service → llmExecutor → LlmHttpClient(probe) → 本地 stub HTTP 服务。
 * 覆盖：各类型探测头（Bearer / x-api-key+anthropic-version）、模型计数、
 * 401 认证失败、连接拒绝，以及 last_test_* 结果落库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderTestConnectionTest {

    /** JDK HttpURLConnection 401 响应体不排空会污染 keep-alive 连接池，测试 JVM 关闭 keep-alive */
    @BeforeAll
    static void disableKeepAlive() {
        System.setProperty("http.keepAlive", "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static HttpServer server;

    private static final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

    private static final AtomicReference<String> responseBody = new AtomicReference<>("{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"},{\"id\":\"m3\"}]}");

    private static final AtomicInteger responseStatus = new AtomicInteger(200);

    private static int serverPort;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", (HttpExchange exchange) -> {
            lastHeaders.clear();
            // 头名按小写存储，断言做大小写无关比较
            exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), String.join(",", v)));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        // 只删本类夹具（T-{13位时间戳}），禁止全表 DELETE——开发库存有用户真实数据
        cleanupFixtures();
        lastHeaders.clear();
        responseBody.set("{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"},{\"id\":\"m3\"}]}");
        responseStatus.set(200);
    }

    @AfterEach
    void cleanupAfter() {
        // 收尾清掉最后一个用例的夹具（@BeforeEach 只清上一轮）
        cleanupFixtures();
    }

    private void cleanupFixtures() {
        jdbcTemplate.execute("DELETE FROM provider WHERE name REGEXP '^T-[0-9]{10,}$'");
    }

    private long create(String type, String baseUrl, String apiKey) throws Exception {
        String body = """
                {"name":"T-%d","type":"%s","baseUrl":"%s","apiKey":"%s"}
                """.formatted(System.nanoTime(), type, baseUrl, apiKey);
        String resp = mockMvc.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return parse(resp).read("$.data.id", Long.class);
    }

    @Test
    void openai兼容端点探测成功并落库() throws Exception {
        long id = create("openai_compatible", "http://127.0.0.1:" + serverPort, "");

        mockMvc.perform(post("/api/v1/providers/{id}/test-connection", id))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.modelCount").value(3))
                .andExpect(jsonPath("$.data.message").value("连接成功，发现 3 个模型"))
                .andExpect(jsonPath("$.data.latencyMs").isNumber());

        // 探测结果落库，详情接口可见
        mockMvc.perform(get("/api/v1/providers/{id}", id))
                .andExpect(jsonPath("$.data.lastTestOk").value(1))
                .andExpect(jsonPath("$.data.lastTestAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lastTestMessage").value("连接成功，发现 3 个模型"));
    }

    @Test
    void claude类型带正确鉴权头() throws Exception {
        long id = create("claude", "http://127.0.0.1:" + serverPort, "sk-ant-test");

        mockMvc.perform(post("/api/v1/providers/{id}/test-connection", id))
                .andExpect(jsonPath("$.data.ok").value(true));

        // 服务端收到的头：x-api-key + anthropic-version（路径 /v1/models 由 adapter 拼）
        assertThat(lastHeaders).containsEntry("x-api-key", "sk-ant-test")
                .containsEntry("anthropic-version", "2023-06-01");
    }

    @Test
    void openai类型带Bearer头() throws Exception {
        long id = create("openai", "http://127.0.0.1:" + serverPort, "sk-openai-test");

        mockMvc.perform(post("/api/v1/providers/{id}/test-connection", id))
                .andExpect(jsonPath("$.data.ok").value(true));

        assertThat(lastHeaders).containsEntry("authorization", "Bearer sk-openai-test");
    }

    @Test
    void 认证失败返回ok为false并落库() throws Exception {
        responseStatus.set(401);
        responseBody.set("{\"error\":\"invalid api key\"}");
        long id = create("openai", "http://127.0.0.1:" + serverPort, "sk-wrong");

        mockMvc.perform(post("/api/v1/providers/{id}/test-connection", id))
                .andExpect(jsonPath("$.code").value(200))   // 测试失败是业务结果，不是接口异常
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.message").value("认证失败，请检查 API Key"));

        mockMvc.perform(get("/api/v1/providers/{id}", id))
                .andExpect(jsonPath("$.data.lastTestOk").value(0))
                .andExpect(jsonPath("$.data.lastTestMessage").value("认证失败，请检查 API Key"));
    }

    @Test
    void 连接拒绝按超时归类() throws Exception {
        // 找一个空闲端口并关闭，制造连接拒绝
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        long id = create("openai_compatible", "http://127.0.0.1:" + freePort, "");

        mockMvc.perform(post("/api/v1/providers/{id}/test-connection", id))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.message").value("连接超时，请检查地址与网络"));
    }
}
