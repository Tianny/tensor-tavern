package com.hify.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.chat.dto.ChatSessionCreateReq;
import com.hify.chat.dto.ChatSessionResp;
import com.hify.chat.dto.ChatStreamResult;
import com.hify.chat.exception.StreamAbortedException;
import com.hify.chat.service.ChatService;
import com.hify.chat.service.ChatStreamListener;
import com.hify.common.exception.BizException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

import static com.jayway.jsonpath.JsonPath.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话流端到端：ChatService → llmExecutor → 熔断/重试外壳 → LlmStreamService 适配器
 * → LlmHttpClient(OkHttp 流式) → 本地 stub（openai 兼容 SSE / claude SSE）。
 * 覆盖：token 流回传、usage 解析、上下文组装（system + Redis 历史）、会话标题、
 * 用户/assistant 消息落库、客户端断开保留部分内容、LLM 失败、不支持类型、游标分页。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamTest {

    /** 收到 stub 最近一次请求体（断言上下文组装） */
    private static final AtomicReference<String> lastRequestBody = new AtomicReference<>("");

    private static HttpServer stubServer;

    private static int stubPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    private long chatModelId;

    private long claudeModelId;

    private long agentId;

    @BeforeAll
    static void startStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubServer.createContext("/v1/chat/completions", ChatStreamTest::handleOpenAi);
        stubServer.createContext("/v1/messages", ChatStreamTest::handleClaude);
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        stubServer.stop(0);
    }

    /** openai 兼容端点：校验鉴权头，回放固定 SSE 流 */
    private static void handleOpenAi(HttpExchange exchange) throws IOException {
        if (!checkAuth(exchange, "Bearer sk-test-key")) {
            respond(exchange, 401, " unauthorized");
            return;
        }
        readBody(exchange);
        respondSse(exchange, List.of(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"让我先想想\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"，我是\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"验证助手\"}}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}",
                "data: [DONE]"));
    }

    /** claude 端点：校验 x-api-key，回放事件流 */
    private static void handleClaude(HttpExchange exchange) throws IOException {
        if (!checkAuth(exchange, null) || !exchange.getRequestHeaders().getFirst("anthropic-version").equals("2023-06-01")) {
            respond(exchange, 401, " unauthorized");
            return;
        }
        readBody(exchange);
        respondSse(exchange, List.of(
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":8}}}",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Claude\"}}",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" 回复\"}}",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":3}}",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}"));
    }

    private static void readBody(HttpExchange exchange) throws IOException {
        lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static boolean checkAuth(HttpExchange exchange, String bearer) {
        if (bearer != null) {
            return bearer.equals(exchange.getRequestHeaders().getFirst("Authorization"));
        }
        return "sk-claude-key".equals(exchange.getRequestHeaders().getFirst("x-api-key"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondSse(HttpExchange exchange, List<String> lines) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String line : lines) {
                out.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /** 只清本测试夹具（Agent 按名字、模型/提供商按前缀精确匹配）。禁止全表 DELETE——
     *  开发库与真实数据共用，全表清空会连带删掉用户真实数据（曾发生事故，见恢复脚本） */
    private void cleanupFixtures() {
        String testAgents = "('chat测试Agent','claude测试Agent','deadAgent','geminiAgent')";
        jdbcTemplate.execute("DELETE FROM chat_message WHERE session_id IN "
                + "(SELECT id FROM chat_session WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE name IN " + testAgents + "))");
        jdbcTemplate.execute("DELETE FROM chat_session WHERE agent_id IN "
                + "(SELECT id FROM agent WHERE name IN " + testAgents + ")");
        jdbcTemplate.execute("DELETE FROM agent WHERE name IN " + testAgents);
        jdbcTemplate.execute("DELETE FROM model_config WHERE model_name LIKE 'chat-test-%'");
        jdbcTemplate.execute("DELETE FROM provider WHERE name LIKE 'chat-stub-%'");
    }

    @BeforeEach
    void setup() throws Exception {
        cleanupFixtures();

        long chatProvider = createProvider("chat-stub-openai",
                "{\"name\":\"chat-stub-openai\",\"type\":\"openai_compatible\",\"baseUrl\":\"http://localhost:%d/v1\",\"apiKey\":\"sk-test-key\"}");
        chatModelId = createModel(chatProvider, "chat-test-model", 128000);
        long claudeProvider = createProvider("chat-stub-claude",
                "{\"name\":\"chat-stub-claude\",\"type\":\"claude\",\"baseUrl\":\"http://localhost:%d\",\"apiKey\":\"sk-claude-key\"}");
        claudeModelId = createModel(claudeProvider, "chat-test-claude", 200000);
        agentId = createAgent(chatModelId);
    }

    @AfterEach
    void cleanup() {
        cleanupFixtures();
    }

    private long createProvider(String name, String body) throws Exception {
        String resp = postJson("/api/v1/providers", body.formatted(stubPort));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createModel(long providerId, String name, int contextTokens) throws Exception {
        String resp = postJson("/api/v1/model-configs", """
                {"providerId":%d,"modelName":"%s","modelType":"chat","maxContextTokens":%d}
                """.formatted(providerId, name, contextTokens));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createAgent(long modelId) throws Exception {
        String resp = postJson("/api/v1/agents", """
                {"name":"chat测试Agent","modelConfigId":%d,"systemPrompt":"你是测试助手",
                 "temperature":0.5,"maxTokens":100}
                """.formatted(modelId));
        return parse(resp).read("$.data.id", Long.class);
    }

    private String postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
    }

    private long createSession(long agentId) throws Exception {
        String resp = postJson("/api/v1/sessions", "{\"agentId\":%d}".formatted(agentId));
        return parse(resp).read("$.data.id", Long.class);
    }

    private CompletableFuture<ChatStreamResult> send(Long sessionId, String content,
            StringBuilder collector) {
        return chatService.streamChat(sessionId, content, collector::append);
    }

    // ---------- 用例 ----------

    @Test
    void 流式对话与落库与标题() throws Exception {
        long sessionId = createSession(agentId);
        StringBuilder tokens = new StringBuilder();

        ChatStreamResult result = send(sessionId, "第一条问题：你是谁", tokens).get(10, TimeUnit.SECONDS);

        assertThat(result.isOk()).isTrue();
        assertThat(tokens.toString()).isEqualTo("你好，我是验证助手");
        assertThat(result.getContent()).isEqualTo("你好，我是验证助手");
        assertThat(result.getPromptTokens()).isEqualTo(12);
        assertThat(result.getCompletionTokens()).isEqualTo(6);
        assertThat(result.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getMessageId()).isNotNull();

        // 两条消息落库：user + assistant（带 usage 与耗时）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = " + sessionId, Integer.class);
        assertThat(count).isEqualTo(2);
        Integer latency = jdbcTemplate.queryForObject(
                "SELECT latency_ms FROM chat_message WHERE session_id = " + sessionId + " AND role = 'assistant'",
                Integer.class);
        assertThat(latency).isGreaterThanOrEqualTo(0);

        // 首条消息生成会话标题
        String title = jdbcTemplate.queryForObject(
                "SELECT title FROM chat_session WHERE id = " + sessionId, String.class);
        assertThat(title).isEqualTo("第一条问题：你是谁");

        // 请求体：system prompt + 本轮 user
        JsonNode request = objectMapper.readTree(lastRequestBody.get());
        assertThat(request.path("model").asText()).isEqualTo("chat-test-model");
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("max_tokens").asInt()).isEqualTo(100);
        assertThat(request.path("temperature").asDouble()).isEqualTo(0.5);
        assertThat(request.path("stream_options").path("include_usage").asBoolean()).isTrue();
        JsonNode messages = request.path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("你是测试助手");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("第一条问题：你是谁");
    }

    @Test
    void 思考流透传且不落库() throws Exception {
        long sessionId = createSession(agentId);
        StringBuilder reasoning = new StringBuilder();
        ChatStreamResult result = chatService.streamChat(sessionId, "思考一下", new ChatStreamListener() {
            @Override
            public void onToken(String delta) {
                // 只关心思考流
            }

            @Override
            public void onReasoning(String delta) {
                reasoning.append(delta);
            }
        }).get(10, TimeUnit.SECONDS);

        assertThat(result.isOk()).isTrue();
        assertThat(reasoning.toString()).isEqualTo("让我先想想");
        // 思考流不落库、不混入正文
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM chat_message WHERE session_id = " + sessionId
                        + " AND role = 'assistant'", String.class);
        assertThat(content).isEqualTo("你好，我是验证助手");
    }

    @Test
    void 第二轮携带Redis上下文() throws Exception {
        long sessionId = createSession(agentId);
        send(sessionId, "第一轮", new StringBuilder()).get(10, TimeUnit.SECONDS);
        send(sessionId, "第二轮", new StringBuilder()).get(10, TimeUnit.SECONDS);

        // 第二次请求的 messages：system + 第一轮 user/assistant + 本轮 user
        JsonNode messages = objectMapper.readTree(lastRequestBody.get()).path("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("第一轮");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("你好，我是验证助手");
        assertThat(messages.get(3).path("content").asText()).isEqualTo("第二轮");
    }

    @Test
    void claude适配器() throws Exception {
        // 换绑 claude 模型的 Agent
        long claudeAgent = parse(postJson("/api/v1/agents", """
                {"name":"claude测试Agent","modelConfigId":%d,"systemPrompt":"你是claude","maxTokens":0}
                """.formatted(claudeModelId))).read("$.data.id", Long.class);
        long sessionId = createSession(claudeAgent);
        StringBuilder tokens = new StringBuilder();

        ChatStreamResult result = send(sessionId, "hi", tokens).get(10, TimeUnit.SECONDS);

        assertThat(result.isOk()).isTrue();
        assertThat(tokens.toString()).isEqualTo("Claude 回复");
        assertThat(result.getPromptTokens()).isEqualTo(8);
        assertThat(result.getCompletionTokens()).isEqualTo(3);

        // claude 请求体：system 顶层、max_tokens 必填兜底
        JsonNode request = objectMapper.readTree(lastRequestBody.get());
        assertThat(request.path("system").asText()).isEqualTo("你是claude");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void 客户端断开保留部分内容() throws Exception {
        long sessionId = createSession(agentId);
        // 第一个 token 就断开（模拟 SseEmitter.send 失败）
        ChatStreamResult result = chatService.streamChat(sessionId, "断开测试", delta -> {
            throw new StreamAbortedException(new RuntimeException("client gone"));
        }).get(10, TimeUnit.SECONDS);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getContent()).isEqualTo("你好");   // 中止前已累积的部分
        assertThat(result.getMessageId()).isNotNull();       // 部分内容照常落库
        Integer assistantRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = " + sessionId
                        + " AND role = 'assistant'", Integer.class);
        assertThat(assistantRows).isEqualTo(1);
    }

    @Test
    void LLM失败只留用户消息() throws Exception {
        // 指向必失败端口（连接被拒 → CALL_FAILED，不重试）
        long provider = createProvider("chat-stub-dead",
                "{\"name\":\"chat-stub-dead\",\"type\":\"openai_compatible\",\"baseUrl\":\"http://localhost:9/v1\"}");
        long model = createModel(provider, "chat-test-dead", 0);
        long deadAgent = parse(postJson("/api/v1/agents",
                "{\"name\":\"deadAgent\",\"modelConfigId\":%d}".formatted(model)))
                .read("$.data.id", Long.class);
        long sessionId = createSession(deadAgent);

        ChatStreamResult result = send(sessionId, "会失败的消息", new StringBuilder()).get(15, TimeUnit.SECONDS);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
        assertThat(result.getMessageId()).isNull();
        Integer roles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = " + sessionId, Integer.class);
        assertThat(roles).isEqualTo(1);   // 只有 user
    }

    @Test
    void 不支持的提供商类型同步拦截() throws Exception {
        long provider = createProvider("chat-stub-gemini",
                "{\"name\":\"chat-stub-gemini\",\"type\":\"gemini\",\"apiKey\":\"g-key\"}");
        long model = createModel(provider, "chat-test-gemini", 0);
        long geminiAgent = parse(postJson("/api/v1/agents",
                "{\"name\":\"geminiAgent\",\"modelConfigId\":%d}".formatted(model)))
                .read("$.data.id", Long.class);
        long sessionId = createSession(geminiAgent);

        // 同步段抛 4003（此时响应还没切成 SSE，全局异常处理器回 Result JSON）
        assertThatThrownBy(() -> chatService.streamChat(sessionId, "hi", t -> { }))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持");
        mockMvc.perform(post("/api/v1/sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(jsonPath("$.code").value(4003));
    }

    @Test
    void 会话与消息游标分页() throws Exception {
        long sessionId = createSession(agentId);
        // MockMvc 走非流式接口验证会话管理
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sessions").param("agentId", String.valueOf(agentId)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].title").value("新对话"));

        send(sessionId, "第一轮", new StringBuilder()).get(10, TimeUnit.SECONDS);
        send(sessionId, "第二轮", new StringBuilder()).get(10, TimeUnit.SECONDS);

        // 游标分页：4 条消息，limit=2 → 最新 2 条（时间正序），再往前翻 2 条
        String page1 = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sessions/{id}/messages", sessionId)
                        .param("limit", "2"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids1 = parse(page1).read("$.data[*].id");
        String page2 = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sessions/{id}/messages", sessionId)
                        .param("limit", "2").param("before", String.valueOf(ids1.get(0))))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids2 = parse(page2).read("$.data[*].id");
        int older = ids2.get(1);
        assertThat(older).isLessThan(ids1.get(0).intValue());
        // 两页角色均为 user / assistant 交替
        assertThat(parse(page1).read("$.data[0].role", String.class)).isEqualTo("user");

        // 删除会话
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/sessions/{id}", sessionId))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/sessions").param("agentId", String.valueOf(agentId)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
