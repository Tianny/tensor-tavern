package com.hify.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.chat.dto.ToolCallEvent;
import com.hify.chat.dto.ToolResultEvent;
import com.hify.chat.dto.ChatStreamResult;
import com.hify.chat.service.ChatService;
import com.hify.chat.service.ChatStreamListener;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.jayway.jsonpath.JsonPath.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 工具接入端到端：服务 CRUD 与校验 → 连通性测试（握手 + tools/list，
 * 响应走 SSE 帧形态）→ Agent 绑定 → 对话全流式 function calling
 * （openai 的 tool_calls 分片 / claude 的 tool_use + input_json_delta 分片）
 * → 工具失败报错不降级 → 模型幻觉未知工具自纠。
 *
 * <p>stub 三个上下文：/mcp（JSON-RPC MCP 服务，echo/boom 两工具）、
 * /chat/completions（openai 流式）、/v1/messages（claude 流式）。
 * LLM stub 按请求内容决策：无 tool 消息且有 tools → 逐片发 tool_calls
 * （调用 tools[0]，用户说"炸"调 tools[1]，说"幽灵"调不存在的名字）；
 * 已有 tool 消息 → 以"工具说：{结果}"作答。
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpTest {

    private static final String MCP_PREFIX = "mcp测试-";

    /** stub 收到最近一次 LLM 请求体（断言工具循环的消息回传格式） */
    private static final AtomicReference<String> lastLlmBody = new AtomicReference<>("");

    private static HttpServer stubServer;

    private static int stubPort;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatService chatService;

    // 事件记录（工具卡片 / 流式 token 断言）
    private final List<ToolCallEvent> toolCallEvents = Collections.synchronizedList(new ArrayList<>());

    private final List<ToolResultEvent> toolResultEvents = Collections.synchronizedList(new ArrayList<>());

    private final List<String> tokenLog = Collections.synchronizedList(new ArrayList<>());

    private long openaiModelId;

    private long claudeModelId;

    @BeforeAll
    static void startStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubServer.createContext("/mcp", McpTest::handleMcp);
        stubServer.createContext("/chat/completions", McpTest::handleOpenAi);
        stubServer.createContext("/v1/messages", McpTest::handleClaude);
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        stubServer.stop(0);
    }

    // ---------- MCP JSON-RPC stub ----------

    private static void handleMcp(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode req;
        try {
            req = MAPPER.readTree(body);
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"bad json\"}", "application/json");
            return;
        }
        String method = req.path("method").asText("");
        long id = req.path("id").asLong(0);
        switch (method) {
            case "initialize" -> {
                // 响应头带会话 id（握手后客户端缓存，后续请求回传）
                exchange.getResponseHeaders().set("Mcp-Session-Id", "stub-session-1");
                respond(exchange, 200, jsonRpc(id, Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "serverInfo", Map.of("name", "stub-mcp", "version", "1.0"))), "application/json");
            }
            case "notifications/initialized" ->
                    // 通知无响应体：202
                    exchange.sendResponseHeaders(202, -1);
            case "tools/list" ->
                    // 刻意走 SSE 帧形态（text/event-stream + data: 行），验证客户端两种响应解析
                    respondSse(exchange, "data: " + jsonRpc(id, Map.of("tools", List.of(
                            Map.of(
                                    "name", "echo",
                                    "description", "回声工具",
                                    "inputSchema", Map.of(
                                            "type", "object",
                                            "properties", Map.of("message", Map.of("type", "string")),
                                            "required", List.of("message"))),
                            Map.of(
                                    "name", "boom",
                                    "description", "总是失败的工具",
                                    "inputSchema", Map.of("type", "object"))))) + "\n\n");
            case "tools/call" -> {
                String tool = req.path("params").path("name").asText("");
                if ("echo".equals(tool)) {
                    String message = req.path("params").path("arguments").path("message").asText("");
                    respond(exchange, 200, jsonRpc(id, Map.of(
                            "content", List.of(Map.of("type", "text", "text", "回声:" + message)),
                            "isError", false)), "application/json");
                } else if ("boom".equals(tool)) {
                    respond(exchange, 200, jsonRpc(id, Map.of(
                            "content", List.of(Map.of("type", "text", "text", "炸了")),
                            "isError", true)), "application/json");
                } else {
                    respond(exchange, 200, rpcError(id, -32602, "Unknown tool: " + tool), "application/json");
                }
            }
            default -> respond(exchange, 200, rpcError(id, -32601, "Method not found"), "application/json");
        }
        exchange.close();
    }

    // ---------- openai 流式 stub（function calling） ----------

    private static void handleOpenAi(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastLlmBody.set(body);
        List<String> frames = new ArrayList<>();
        boolean hasToolMessages = body.contains("\"role\":\"tool\"");
        if (hasToolMessages) {
            // 收尾轮：以"工具说：{最后一条工具结果}"作答（分两个 token 增量）
            String toolResult = lastToolContent(body);
            frames.add(frame(Map.of("content", "工具说：")));
            frames.add(frame(Map.of("content", toolResult)));
            frames.add(frame(Map.of()));
        } else if (body.contains("\"tools\":[")) {
            // 工具决策轮：先说一句 narration，再逐片发 tool_calls
            String toolName = pickTool(body);
            String arguments = argumentsJson();
            String first = arguments.substring(0, 4);
            String rest = arguments.substring(4);
            frames.add(frame(Map.of("content", "让我查一下\n")));
            frames.add(frame(Map.of("tool_calls", List.of(Map.of(
                    "index", 0, "id", "call-echo-1", "type", "function",
                    "function", Map.of("name", toolName, "arguments", ""))))));
            frames.add(frame(Map.of("tool_calls", List.of(Map.of(
                    "index", 0, "function", Map.of("arguments", first))))));
            frames.add(frame(Map.of("tool_calls", List.of(Map.of(
                    "index", 0, "function", Map.of("arguments", rest))))));
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

    // ---------- claude 流式 stub（function calling） ----------

    private static void handleClaude(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastLlmBody.set(body);
        StringBuilder stream = new StringBuilder();
        sseEvent(stream, "message_start", Map.of("message", Map.of("usage", Map.of("input_tokens", 11))));
        if (body.contains("\"tool_result\"")) {
            // 收尾轮：tool_result 之后的纯文字回答
            String toolResult = lastToolResult(body);
            sseEvent(stream, "content_block_start", Map.of("content_block", Map.of("type", "text")));
            sseEvent(stream, "content_block_delta", Map.of("delta", Map.of(
                    "type", "text_delta", "text", "工具说：" + toolResult)));
            sseEvent(stream, "content_block_stop", Map.of());
        } else if (body.contains("\"tools\":[")) {
            // 工具决策轮：tool_use 块 + input_json_delta 分片拼参数
            String toolName = pickTool(body);
            String arguments = argumentsJson();
            String first = arguments.substring(0, 4);
            String rest = arguments.substring(4);
            sseEvent(stream, "content_block_start", Map.of("content_block", Map.of(
                    "type", "tool_use", "id", "toolu-01", "name", toolName)));
            sseEvent(stream, "content_block_delta", Map.of("delta", Map.of(
                    "type", "input_json_delta", "partial_json", first)));
            sseEvent(stream, "content_block_delta", Map.of("delta", Map.of(
                    "type", "input_json_delta", "partial_json", rest)));
            sseEvent(stream, "content_block_stop", Map.of());
        } else {
            sseEvent(stream, "content_block_start", Map.of("content_block", Map.of("type", "text")));
            sseEvent(stream, "content_block_delta", Map.of("delta", Map.of(
                    "type", "text_delta", "text", "默认回复")));
            sseEvent(stream, "content_block_stop", Map.of());
        }
        sseEvent(stream, "message_delta", Map.of(
                "delta", Map.of("stop_reason", "end_turn"),
                "usage", Map.of("output_tokens", 7)));
        respondSse(exchange, stream.toString());
        exchange.close();
    }

    // ---------- stub 内部工具 ----------

    /** 工具决策：默认 tools[0]；用户说"炸"→ tools[1]；说"幽灵"→ 不存在的名字（幻觉自纠） */
    private static String pickTool(String body) {
        try {
            JsonNode tools = MAPPER.readTree(body).path("tools");
            JsonNode first = tools.path(0);
            String name = first.has("function")
                    ? first.path("function").path("name").asText()
                    : first.path("name").asText();
            if (body.contains("炸")) {
                JsonNode second = tools.path(1);
                return second.has("function")
                        ? second.path("function").path("name").asText()
                        : second.path("name").asText();
            }
            if (body.contains("幽灵")) {
                return name + "-ghost";
            }
            return name;
        } catch (Exception e) {
            return "echo";
        }
    }

    private static String argumentsJson() {
        try {
            return MAPPER.writeValueAsString(Map.of("message", "帮我查天气"));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** openai 请求里最后一条 role=tool 的 content */
    private static String lastToolContent(String body) {
        try {
            JsonNode messages = MAPPER.readTree(body).path("messages");
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode message = messages.get(i);
                if ("tool".equals(message.path("role").asText())) {
                    return message.path("content").asText("");
                }
            }
        } catch (Exception ignored) {
            // 解析失败按空处理
        }
        return "";
    }

    /** claude 请求里最后一个 tool_result 块的 content */
    private static String lastToolResult(String body) {
        try {
            JsonNode messages = MAPPER.readTree(body).path("messages");
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode content = messages.get(i).path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (int j = content.size() - 1; j >= 0; j--) {
                    JsonNode block = content.get(j);
                    if ("tool_result".equals(block.path("type").asText())) {
                        return block.path("content").asText("");
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败按空处理
        }
        return "";
    }

    private static String frame(Map<String, Object> delta) {
        try {
            return "data: " + MAPPER.writeValueAsString(Map.of("choices",
                    List.of(Map.of("index", 0, "delta", delta)))) + "\n\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static String jsonRpc(long id, Object result) {
        try {
            return MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String rpcError(long id, int code, String message) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", id,
                    "error", Map.of("code", code, "message", message)));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void sseEvent(StringBuilder out, String event, Object data) {
        try {
            out.append("event: ").append(event).append('\n');
            out.append("data: ").append(MAPPER.writeValueAsString(data)).append("\n\n");
        } catch (Exception ignored) {
            // 序列化失败跳过该事件
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
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

    @BeforeEach
    void setup() throws Exception {
        cleanupFixtures();
        long openaiProviderId = createProvider("mcp-stub-openai", "openai_compatible");
        openaiModelId = createModel(openaiProviderId, "mcp-test-openai");
        long claudeProviderId = createProvider("mcp-stub-claude", "claude");
        claudeModelId = createModel(claudeProviderId, "mcp-test-claude");
        toolCallEvents.clear();
        toolResultEvents.clear();
        tokenLog.clear();
    }

    @AfterEach
    void cleanup() {
        cleanupFixtures();
    }

    /** 只删测试夹具（精确前缀），用户真实数据不动 */
    private void cleanupFixtures() {
        List<Long> agentIds = jdbcTemplate.queryForList(
                "SELECT id FROM agent WHERE name LIKE '" + MCP_PREFIX + "%'", Long.class);
        for (Long agentId : agentIds) {
            jdbcTemplate.update("DELETE FROM chat_message WHERE session_id IN "
                    + "(SELECT id FROM chat_session WHERE agent_id = " + agentId + ")");
            jdbcTemplate.update("DELETE FROM chat_session WHERE agent_id = " + agentId);
            jdbcTemplate.update("DELETE FROM agent_tool WHERE agent_id = " + agentId);
            jdbcTemplate.update("DELETE FROM agent_knowledge WHERE agent_id = " + agentId);
            jdbcTemplate.update("DELETE FROM agent WHERE id = " + agentId);
        }
        jdbcTemplate.execute("DELETE FROM agent_tool WHERE mcp_server_id IN "
                + "(SELECT id FROM mcp_server WHERE name LIKE '" + MCP_PREFIX + "%')");
        jdbcTemplate.execute("DELETE FROM mcp_server WHERE name LIKE '" + MCP_PREFIX + "%'");
        jdbcTemplate.execute("DELETE FROM model_config WHERE model_name LIKE 'mcp-test-%'");
        jdbcTemplate.execute("DELETE FROM provider WHERE name LIKE 'mcp-stub-%'");
    }

    private long createProvider(String name, String type) throws Exception {
        String resp = postJson("/api/v1/providers", """
                {"name":"%s","type":"%s","baseUrl":"http://localhost:%d","apiKey":"sk-mcp-key"}
                """.formatted(name, type, stubPort));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createModel(long providerId, String modelName) throws Exception {
        String resp = postJson("/api/v1/model-configs", """
                {"providerId":%d,"modelName":"%s","modelType":"chat","maxContextTokens":32000}
                """.formatted(providerId, modelName));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createAgent(String name, long modelId) throws Exception {
        String resp = postJson("/api/v1/agents", """
                {"name":"%s","description":"MCP 测试","modelConfigId":%d,
                 "systemPrompt":"你是测试助手","temperature":0.3,"maxTokens":0,"enabled":1}
                """.formatted(name, modelId));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createMcpServer(String name) throws Exception {
        String resp = postJson("/api/v1/mcp-servers", """
                {"name":"%s","description":"stub 服务","transport":"http",
                 "url":"http://localhost:%d/mcp","headers":{"Authorization":"Bearer sk-mcp-1"},"enabled":1}
                """.formatted(name, stubPort));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createAgentSession(long agentId) throws Exception {
        String resp = postJson("/api/v1/sessions", "{\"agentId\":" + agentId + "}");
        return parse(resp).read("$.data.id", Long.class);
    }

    private void bindAgentTools(long agentId, long serverId, String toolName) throws Exception {
        String binding = toolName == null ? "" : ",\"toolName\":\"" + toolName + "\"";
        putJson("/api/v1/agents/" + agentId + "/tools",
                "{\"bindings\":[{\"mcpServerId\":" + serverId + binding + "}]}");
    }

    /** 记录全部事件的 listener（token / 工具事件） */
    private ChatStreamListener recordingListener() {
        return new ChatStreamListener() {
            @Override
            public void onToken(String delta) {
                tokenLog.add(delta);
            }

            @Override
            public void onToolCall(ToolCallEvent event) {
                toolCallEvents.add(event);
            }

            @Override
            public void onToolResult(ToolResultEvent event) {
                toolResultEvents.add(event);
            }
        };
    }

    // ---------- 通用请求 ----------

    private String postJson(String url, String body) throws Exception {
        return sendJson(MockMvcRequestBuilders.post(url), body);
    }

    private String putJson(String url, String body) throws Exception {
        return sendJson(MockMvcRequestBuilders.put(url), body);
    }

    /** JSON 请求体一律传 UTF-8 字节：MockMvc 的 content(String) 默认按 ISO-8859-1 编码，中文会变问号 */
    private String sendJson(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 不校验 code 的 JSON 请求（用于断言错误码） */
    private String sendJsonRaw(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String get(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ---------- 测试 ----------

    @Test
    void MCP服务CRUD与校验() throws Exception {
        // live 库里可能有用户真实数据（如验证用的「本地工具」），断言一律基线+增量
        int baseline = parse(get("/api/v1/mcp-servers")).read("$.data.length()", Integer.class);
        long serverId = createMcpServer(MCP_PREFIX + "服务");
        // 详情：headers 值掩码回显
        String detail = get("/api/v1/mcp-servers/" + serverId);
        assertThat(parse(detail).read("$.data.name", String.class)).isEqualTo(MCP_PREFIX + "服务");
        assertThat(parse(detail).read("$.data.headers.Authorization", String.class)).isEqualTo("******");

        // 更新时掩码值原样传回 → 库里真实值保持
        putJson("/api/v1/mcp-servers/" + serverId, """
                {"name":"%s","description":"stub 服务","url":"http://localhost:%d/mcp",
                 "headers":{"Authorization":"******"},"enabled":1}
                """.formatted(MCP_PREFIX + "服务", stubPort));
        String rawHeader = jdbcTemplate.queryForObject(
                "SELECT headers FROM mcp_server WHERE id = " + serverId, String.class);
        assertThat(rawHeader).contains("Bearer sk-mcp-1").doesNotContain("\"******\"");

        // 列表可见（基线 + 1）
        String list = get("/api/v1/mcp-servers");
        assertThat(parse(list).read("$.data.length()", Integer.class)).isEqualTo(baseline + 1);

        // 重名 → 5002
        String dup = sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/mcp-servers"), """
                {"name":"%s","url":"http://localhost:%d/mcp"}
                """.formatted(MCP_PREFIX + "服务", stubPort));
        assertThat(parse(dup).read("$.code", Integer.class)).isEqualTo(5002);

        // stdio 未实现 → 5004（url 给合法值，让 transport 校验先于 Bean 校验命中）
        String stdio = sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/mcp-servers"), """
                {"name":"%s","transport":"stdio","url":"http://localhost:%d/mcp","command":"npx xx"}
                """.formatted(MCP_PREFIX + "stdio", stubPort));
        assertThat(parse(stdio).read("$.code", Integer.class)).isEqualTo(5004);

        // 非 http(s) 地址 → 5003
        String badUrl = sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/mcp-servers"), """
                {"name":"%s","url":"localhost:9999"}
                """.formatted(MCP_PREFIX + "坏地址"));
        assertThat(parse(badUrl).read("$.code", Integer.class)).isEqualTo(5003);

        // headers 非对象 → 5007
        String badHeaders = sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/mcp-servers"), """
                {"name":"%s","url":"http://localhost:%d/mcp","headers":[1,2]}
                """.formatted(MCP_PREFIX + "坏头", stubPort));
        assertThat(parse(badHeaders).read("$.code", Integer.class)).isEqualTo(5007);

        // 删除后不可见（回到基线）
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/mcp-servers/" + serverId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(parse(get("/api/v1/mcp-servers")).read("$.data.length()", Integer.class))
                .isEqualTo(baseline);
    }

    @Test
    void 连通性测试与工具列表() throws Exception {
        long serverId = createMcpServer(MCP_PREFIX + "服务");
        // 握手 + tools/list 成功（tools/list 走 SSE 帧响应，验证两种响应形态解析）
        String test = postJson("/api/v1/mcp-servers/" + serverId + "/test-connection", "{}");
        assertThat(parse(test).read("$.data.ok", Boolean.class)).isTrue();
        assertThat(parse(test).read("$.data.toolCount", Integer.class)).isEqualTo(2);

        String tools = get("/api/v1/mcp-servers/" + serverId + "/tools");
        assertThat(parse(tools).read("$.data.length()", Integer.class)).isEqualTo(2);
        assertThat(parse(tools).read("$.data[0].name", String.class)).isEqualTo("echo");
        assertThat(parse(tools).read("$.data[0].description", String.class)).isEqualTo("回声工具");
        assertThat(parse(tools).read("$.data[0].inputSchema.required[0]", String.class)).isEqualTo("message");

        // 停用后拉工具 → 5005
        jdbcTemplate.update("UPDATE mcp_server SET enabled = 0 WHERE id = " + serverId);
        String disabled = sendJsonRaw(MockMvcRequestBuilders.get(
                "/api/v1/mcp-servers/" + serverId + "/tools"), "");
        assertThat(parse(disabled).read("$.code", Integer.class)).isEqualTo(5005);

        // 不可达地址：测试失败 ok=false（不是异常）
        long deadId = parse(sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/mcp-servers"), """
                {"name":"%s","url":"http://localhost:1/mcp"}
                """.formatted(MCP_PREFIX + "死服务"))).read("$.data.id", Long.class);
        String deadTest = postJson("/api/v1/mcp-servers/" + deadId + "/test-connection", "{}");
        assertThat(parse(deadTest).read("$.data.ok", Boolean.class)).isFalse();
    }

    @Test
    void Agent工具绑定() throws Exception {
        long agentId = createAgent(MCP_PREFIX + "代理", openaiModelId);
        long serverId = createMcpServer(MCP_PREFIX + "服务");

        // 绑全部工具（toolName 空）
        bindAgentTools(agentId, serverId, null);
        String bound = get("/api/v1/agents/" + agentId + "/tools");
        assertThat(parse(bound).read("$.data.length()", Integer.class)).isEqualTo(1);
        assertThat(parse(bound).read("$.data[0].mcpServerId", Long.class)).isEqualTo(serverId);
        assertThat(parse(bound).read("$.data[0].serverName", String.class)).isEqualTo(MCP_PREFIX + "服务");
        assertThat(parse(bound).read("$.data[0].toolName", Object.class)).isNull();

        // 换绑指定工具
        bindAgentTools(agentId, serverId, "echo");
        assertThat(parse(get("/api/v1/agents/" + agentId + "/tools"))
                .read("$.data[0].toolName", String.class)).isEqualTo("echo");

        // 空数组 = 全部解绑
        putJson("/api/v1/agents/" + agentId + "/tools", "{\"bindings\":[]}");
        assertThat(parse(get("/api/v1/agents/" + agentId + "/tools"))
                .read("$.data.length()", Integer.class)).isZero();

        // 绑不存在的服务 → 5001
        String notFound = sendJsonRaw(MockMvcRequestBuilders.put(
                "/api/v1/agents/" + agentId + "/tools"),
                "{\"bindings\":[{\"mcpServerId\":999999}]}");
        assertThat(parse(notFound).read("$.code", Integer.class)).isEqualTo(5001);

        // 绑停用的服务 → 5005
        jdbcTemplate.update("UPDATE mcp_server SET enabled = 0 WHERE id = " + serverId);
        String disabled = sendJsonRaw(MockMvcRequestBuilders.put(
                "/api/v1/agents/" + agentId + "/tools"),
                "{\"bindings\":[{\"mcpServerId\":" + serverId + "}]}");
        assertThat(parse(disabled).read("$.code", Integer.class)).isEqualTo(5005);
    }

    @Test
    void 对话工具调用openai流式() throws Exception {
        long serverId = createMcpServer(MCP_PREFIX + "服务");
        long agentId = createAgent(MCP_PREFIX + "代理", openaiModelId);
        bindAgentTools(agentId, serverId, null);   // 全部工具
        long sessionId = createAgentSession(agentId);

        ChatStreamResult result = chatService
                .streamChat(sessionId, "北京天气", recordingListener())
                .get(20, java.util.concurrent.TimeUnit.SECONDS);

        // 成功：narration + 收尾回答（工具结果回传后模型作答）
        assertThat(result.isOk()).isTrue();
        assertThat(result.getContent()).contains("让我查一下").contains("工具说：回声:帮我查天气");
        // token 是流式逐段推的
        assertThat(String.join("", tokenLog)).isEqualTo(result.getContent());

        // 工具事件：调用 echo + 结果回执
        assertThat(toolCallEvents).hasSize(1);
        assertThat(toolCallEvents.get(0).serverName()).isEqualTo(MCP_PREFIX + "服务");
        assertThat(toolCallEvents.get(0).toolName()).isEqualTo("echo");
        assertThat(toolCallEvents.get(0).arguments()).contains("帮我查天气");
        assertThat(toolResultEvents).hasSize(1);
        assertThat(toolResultEvents.get(0).ok()).isTrue();
        assertThat(toolResultEvents.get(0).result()).isEqualTo("回声:帮我查天气");

        // 第二轮请求体：assistant 工具调用消息（含 narration）+ role=tool 结果回传
        assertThat(lastLlmBody.get()).contains("\"role\":\"tool\"");
        assertThat(lastLlmBody.get()).contains("tool_call_id");
        assertThat(lastLlmBody.get()).contains("让我查一下");
        assertThat(lastLlmBody.get()).contains("回声:帮我查天气");

        // 消息落库：user + assistant
        String messages = get("/api/v1/sessions/" + sessionId + "/messages");
        assertThat(parse(messages).read("$.data.length()", Integer.class)).isEqualTo(2);
        assertThat(parse(messages).read("$.data[1].content", String.class))
                .isEqualTo(result.getContent());
    }

    @Test
    void 对话工具调用claude流式() throws Exception {
        long serverId = createMcpServer(MCP_PREFIX + "服务");
        long agentId = createAgent(MCP_PREFIX + "代理", claudeModelId);
        bindAgentTools(agentId, serverId, null);
        long sessionId = createAgentSession(agentId);

        ChatStreamResult result = chatService
                .streamChat(sessionId, "上海天气", recordingListener())
                .get(20, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getContent()).isEqualTo("工具说：回声:帮我查天气");

        assertThat(toolCallEvents).hasSize(1);
        assertThat(toolCallEvents.get(0).toolName()).isEqualTo("echo");
        assertThat(toolResultEvents.get(0).result()).isEqualTo("回声:帮我查天气");

        // 第二轮请求体：tool_result 块回传（claude 消息格式）
        assertThat(lastLlmBody.get()).contains("\"tool_result\"");
        assertThat(lastLlmBody.get()).contains("tool_use_id");
        assertThat(lastLlmBody.get()).contains("回声:帮我查天气");

        String messages = get("/api/v1/sessions/" + sessionId + "/messages");
        assertThat(parse(messages).read("$.data[1].content", String.class))
                .isEqualTo("工具说：回声:帮我查天气");
    }

    @Test
    void 工具执行失败报错不降级() throws Exception {
        long serverId = createMcpServer(MCP_PREFIX + "服务");
        long agentId = createAgent(MCP_PREFIX + "代理", openaiModelId);
        bindAgentTools(agentId, serverId, null);
        long sessionId = createAgentSession(agentId);

        // 用户说"炸" → 模型调 boom → stub 返回 isError=true → 对话失败不降级
        ChatStreamResult result = chatService
                .streamChat(sessionId, "炸", recordingListener())
                .get(20, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getErrorMessage()).contains("炸了");
        assertThat(toolCallEvents).hasSize(1);
        assertThat(toolCallEvents.get(0).toolName()).isEqualTo("boom");

        // 失败前已流出的 narration 照常落库（部分内容保留，与既有语义一致），但不产生兜底回答
        String messages = get("/api/v1/sessions/" + sessionId + "/messages");
        assertThat(parse(messages).read("$.data.length()", Integer.class)).isEqualTo(2);
        assertThat(parse(messages).read("$.data[0].role", String.class)).isEqualTo("user");
        assertThat(parse(messages).read("$.data[1].role", String.class)).isEqualTo("assistant");
        assertThat(parse(messages).read("$.data[1].content", String.class)).isEqualTo("让我查一下\n");
    }

    @Test
    void 模型幻觉未知工具自纠() throws Exception {
        long serverId = createMcpServer(MCP_PREFIX + "服务");
        long agentId = createAgent(MCP_PREFIX + "代理", openaiModelId);
        bindAgentTools(agentId, serverId, null);
        long sessionId = createAgentSession(agentId);

        // 用户说"幽灵" → 模型调不存在的工具 → 错误作为工具结果回传 → 模型据此作答
        ChatStreamResult result = chatService
                .streamChat(sessionId, "幽灵", recordingListener())
                .get(20, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getContent()).contains("不存在");
        assertThat(toolCallEvents).hasSize(1);
        assertThat(toolCallEvents.get(0).toolName()).endsWith("-ghost");
        // 未知工具走 ok=false 的本地结果事件
        assertThat(toolResultEvents.get(0).ok()).isFalse();
    }
}
