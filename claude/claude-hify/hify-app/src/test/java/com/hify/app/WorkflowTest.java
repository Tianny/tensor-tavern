package com.hify.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.chat.dto.ChatStreamResult;
import com.hify.chat.service.ChatService;
import com.hify.chat.service.ChatStreamListener;
import com.hify.workflow.entity.WorkflowNode;
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
 * 工作流端到端：CRUD 与图校验 → 顺序执行（模板引用 / 非流式 LLM 调用 / 轨迹落库）
 * → 条件分支路由 → 必填变量同步校验 → LLM 失败归一 → 删除级联清理。
 *
 * <p>LLM stub 为非流式 /chat/completions：按 prompt 内容回放（好评→正面、差评→负面、
 * 回声：X→原样回显、含"炸"→500），模板渲染正确性可从输出反推。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowTest {

    private static final String WF_PREFIX = "wf测试-";

    /** stub 收到最近一次 LLM 请求体（断言模板渲染与 UTF-8 完整性） */
    private static final AtomicReference<String> lastLlmBody = new AtomicReference<>("");

    private static HttpServer stubServer;

    private static int stubPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatService chatService;

    private long chatModelId;

    @BeforeAll
    static void startStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubServer.createContext("/chat/completions", WorkflowTest::handleChat);
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        stubServer.stop(0);
    }

    /** 非流式 chat：按 prompt 关键词回放，验证模板渲染链路 */
    private static void handleChat(HttpExchange exchange) throws IOException {
        if (!"Bearer sk-wf-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastLlmBody.set(body);
        JsonNode content = new ObjectMapper().readTree(body)
                .path("messages").path(0).path("content");
        String prompt = content.isTextual() ? content.asText() : "";
        if (prompt.contains("炸")) {
            respond(exchange, 500, "{\"error\":\"boom\"}");
            return;
        }
        String reply;
        if (prompt.contains("回声：")) {
            reply = "收到：" + prompt.substring(prompt.indexOf("回声：") + 3);
        } else if (prompt.contains("好评")) {
            reply = "正面";
        } else if (prompt.contains("差评")) {
            reply = "负面";
        } else {
            reply = "默认回复";
        }
        respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"" + reply + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @BeforeEach
    void setup() throws Exception {
        cleanupFixtures();
        long providerId = createProvider();
        chatModelId = createModel(providerId);
    }

    @AfterEach
    void cleanup() throws Exception {
        cleanupFixtures();
    }

    /** 只删测试夹具（精确前缀），用户真实工作流 / 模型不动 */
    private void cleanupFixtures() {
        List<Long> wfIds = jdbcTemplate.queryForList(
                "SELECT id FROM workflow WHERE name LIKE '" + WF_PREFIX + "%'", Long.class);
        for (Long wfId : wfIds) {
            jdbcTemplate.update("DELETE FROM chat_message WHERE session_id IN "
                    + "(SELECT id FROM chat_session WHERE workflow_id = " + wfId + ")");
            jdbcTemplate.update("DELETE FROM chat_session WHERE workflow_id = " + wfId);
            jdbcTemplate.update("DELETE FROM workflow_run WHERE workflow_id = ?", wfId);
            jdbcTemplate.update("DELETE FROM workflow_node WHERE workflow_id = ?", wfId);
            jdbcTemplate.update("DELETE FROM workflow_edge WHERE workflow_id = ?", wfId);
            jdbcTemplate.update("DELETE FROM workflow WHERE id = ?", wfId);
        }
        jdbcTemplate.execute("DELETE FROM model_config WHERE model_name LIKE 'wf-test-%'");
        jdbcTemplate.execute("DELETE FROM provider WHERE name LIKE 'wf-stub-%'");
    }

    // ---------- 夹具 ----------

    private long createProvider() throws Exception {
        String resp = postJson("/api/v1/providers", """
                {"name":"wf-stub-chat","type":"openai_compatible","baseUrl":"http://localhost:%d","apiKey":"sk-wf-key"}
                """.formatted(stubPort));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createModel(long providerId) throws Exception {
        String resp = postJson("/api/v1/model-configs", """
                {"providerId":%d,"modelName":"wf-test-chat","modelType":"chat","maxContextTokens":32000}
                """.formatted(providerId));
        return parse(resp).read("$.data.id", Long.class);
    }

    /** 顺序图：start(query) → llm1(回声 prompt) → end(模板) */
    private String buildSequentialBody(String name) {
        return """
                {
                  "name": "%s",
                  "description": "顺序执行测试",
                  "enabled": 1,
                  "nodes": [
                    {"nodeKey":"start","nodeType":"start","name":"入口",
                     "config":{"variables":[{"name":"query","required":true}]}},
                    {"nodeKey":"llm1","nodeType":"llm","name":"回声",
                     "config":{"modelConfigId":%d,"prompt":"回声：${start.query}","temperature":0.3}},
                    {"nodeKey":"end","nodeType":"end","name":"出口",
                     "config":{"outputTemplate":"最终：${llm1.output}"}}
                  ],
                  "edges": [
                    {"sourceNodeKey":"start","targetNodeKey":"llm1","conditionType":"always","conditionExpr":""},
                    {"sourceNodeKey":"llm1","targetNodeKey":"end","conditionType":"always","conditionExpr":""}
                  ]
                }
                """.formatted(name, chatModelId);
    }

    /** 分支图：start(query) → judge(情感判断) → condition → end正 / end负 */
    private String buildBranchBody(String name) {
        return """
                {
                  "name": "%s",
                  "description": "条件分支测试",
                  "enabled": 1,
                  "nodes": [
                    {"nodeKey":"start","nodeType":"start","name":"入口",
                     "config":{"variables":[{"name":"query","required":true}]}},
                    {"nodeKey":"judge","nodeType":"llm","name":"判断",
                     "config":{"modelConfigId":%d,"prompt":"判断评价情感：${start.query}"}},
                    {"nodeKey":"route","nodeType":"condition","name":"路由","config":{}},
                    {"nodeKey":"endPos","nodeType":"end","name":"好评出口",
                     "config":{"outputTemplate":"走了好评分支"}},
                    {"nodeKey":"endNeg","nodeType":"end","name":"差评出口",
                     "config":{"outputTemplate":"走了差评分支"}}
                  ],
                  "edges": [
                    {"sourceNodeKey":"start","targetNodeKey":"judge","conditionType":"always","conditionExpr":""},
                    {"sourceNodeKey":"judge","targetNodeKey":"route","conditionType":"always","conditionExpr":""},
                    {"sourceNodeKey":"route","targetNodeKey":"endPos","conditionType":"expression",
                     "conditionExpr":"${judge.output} == \\"正面\\""},
                    {"sourceNodeKey":"route","targetNodeKey":"endNeg","conditionType":"expression",
                     "conditionExpr":"${judge.output} == \\"负面\\""}
                  ]
                }
                """.formatted(name, chatModelId);
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

    /** 提交运行并轮询到终态（引擎在 llmExecutor 上异步执行） */
    private JsonNode awaitRunTerminal(String wfName, String inputsJson) throws Exception {
        long wfId = workflowIdByName(wfName);
        String runIdResp = postJson("/api/v1/workflows/" + wfId + "/run", inputsJson);
        long runId = parse(runIdResp).read("$.data", Long.class);
        for (int i = 0; i < 100; i++) {
            String resp = get("/api/v1/workflow-runs/" + runId);
            JsonNode run = objectMapper.readTree(resp).path("data");
            if (!"running".equals(run.path("status").asText())) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("运行超时未到终态，workflow=" + wfName);
    }

    private long workflowIdByName(String name) throws Exception {
        String resp = get("/api/v1/workflows");
        for (Object wf : parse(resp).read("$.data", List.class)) {
            if (name.equals(parse(wf).read("$.name", String.class))) {
                return parse(wf).read("$.id", Long.class);
            }
        }
        throw new AssertionError("工作流不存在：" + name);
    }

    // ---------- 测试 ----------

    @Test
    void 工作流CRUD与图校验() throws Exception {
        // 正常创建 + 详情 + 列表 + 整体替换更新
        String resp = postJson("/api/v1/workflows", buildSequentialBody(WF_PREFIX + "顺序"));
        long wfId = parse(resp).read("$.data.id", Long.class);
        assertThat(parse(resp).read("$.data.nodes.length()", Integer.class)).isEqualTo(3);
        String detail = get("/api/v1/workflows/" + wfId);
        assertThat(parse(detail).read("$.data.edges.length()", Integer.class)).isEqualTo(2);

        String updated = putJson("/api/v1/workflows/" + wfId, buildSequentialBody(WF_PREFIX + "顺序"));
        assertThat(parse(updated).read("$.data.name", String.class)).isEqualTo(WF_PREFIX + "顺序");

        // 重名 → 6002
        String dup = sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows"),
                buildSequentialBody(WF_PREFIX + "顺序"));
        assertThat(parse(dup).read("$.code", Integer.class)).isEqualTo(6002);

        // nodeKey 重复 → 6003
        String badNodes = buildSequentialBody(WF_PREFIX + "坏图")
                .replace("\"nodeKey\":\"end\"", "\"nodeKey\":\"llm1\"");
        assertThat(parse(sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows"), badNodes))
                .read("$.code", Integer.class)).isEqualTo(6003);

        // 悬空连线 / 缺 start → 6004
        String dangling = buildSequentialBody(WF_PREFIX + "坏图")
                .replace("\"targetNodeKey\":\"llm1\"", "\"targetNodeKey\":\"ghost\"");
        assertThat(parse(sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows"), dangling))
                .read("$.code", Integer.class)).isEqualTo(6004);

        // 缺 start 节点 → 6004（直接构造，不做字符串手术）
        String noStart = """
                {
                  "name": "%s",
                  "enabled": 1,
                  "nodes": [
                    {"nodeKey":"llm1","nodeType":"llm","name":"L","config":{"modelConfigId":%d,"prompt":"x"}},
                    {"nodeKey":"end","nodeType":"end","name":"E","config":{"outputTemplate":"${llm1.output}"}}
                  ],
                  "edges": [
                    {"sourceNodeKey":"llm1","targetNodeKey":"end","conditionType":"always","conditionExpr":""}
                  ]
                }
                """.formatted(WF_PREFIX + "坏图", chatModelId);
        assertThat(parse(sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows"), noStart))
                .read("$.code", Integer.class)).isEqualTo(6004);

        // LLM 节点绑不存在的模型 → 6007
        String badModel = buildSequentialBody(WF_PREFIX + "坏图")
                .replace("\"modelConfigId\":" + chatModelId, "\"modelConfigId\":999999");
        assertThat(parse(sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows"), badModel))
                .read("$.code", Integer.class)).isEqualTo(6007);

        // 表达式语法错 → 6008（condition 出边；借用分支图把操作符写坏）
        String badExpr = buildBranchBody(WF_PREFIX + "坏图")
                .replace("${judge.output} == \\\"正面\\\"", "${judge.output} ==> \\\"正面\\\"");
        assertThat(parse(sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows"), badExpr))
                .read("$.code", Integer.class)).isEqualTo(6008);

        // 非条件节点的出边带表达式 → 6004（llm1→end 的边改成 expression）
        String exprOnLlm = buildSequentialBody(WF_PREFIX + "坏图")
                .replace("\"targetNodeKey\":\"end\",\"conditionType\":\"always\"",
                        "\"targetNodeKey\":\"end\",\"conditionType\":\"expression\","
                                + "\"conditionExpr\":\"${llm1.output} == \\\"x\\\"\"");
        assertThat(parse(sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows"), exprOnLlm))
                .read("$.code", Integer.class)).isEqualTo(6004);
    }

    @Test
    void 顺序执行与模板引用() throws Exception {
        postJson("/api/v1/workflows", buildSequentialBody(WF_PREFIX + "顺序"));

        JsonNode run = awaitRunTerminal(WF_PREFIX + "顺序",
                "{\"inputs\":{\"query\":\"你好Hify工作流\"}}");
        assertThat(run.path("status").asText()).isEqualTo("succeeded");
        assertThat(run.path("output").asText()).isEqualTo("最终：收到：你好Hify工作流");

        // 轨迹：3 个节点按序执行，llm 节点 input 是渲染后的 prompt（中文完整）
        assertThat(run.path("traces").size()).isEqualTo(3);
        JsonNode llmTrace = run.path("traces").get(1);
        assertThat(llmTrace.path("nodeKey").asText()).isEqualTo("llm1");
        assertThat(llmTrace.path("input").asText()).isEqualTo("回声：你好Hify工作流");
        assertThat(llmTrace.path("output").asText()).isEqualTo("收到：你好Hify工作流");
        assertThat(llmTrace.path("latencyMs").asInt()).isGreaterThanOrEqualTo(0);

        // stub 收到的请求体里模板已渲染、中文未损坏（UTF-8 链路）
        assertThat(lastLlmBody.get()).contains("回声：你好Hify工作流");
    }

    @Test
    void 条件分支路由() throws Exception {
        postJson("/api/v1/workflows", buildBranchBody(WF_PREFIX + "分支"));

        // 好评 → 正面 → endPos
        JsonNode posRun = awaitRunTerminal(WF_PREFIX + "分支",
                "{\"inputs\":{\"query\":\"这次服务好评\"}}");
        assertThat(posRun.path("status").asText()).isEqualTo("succeeded");
        assertThat(posRun.path("output").asText()).isEqualTo("走了好评分支");

        // 差评 → 负面 → endNeg（同一个工作流按条件走不同终点）
        JsonNode negRun = awaitRunTerminal(WF_PREFIX + "分支",
                "{\"inputs\":{\"query\":\"物流太慢，差评\"}}");
        assertThat(negRun.path("status").asText()).isEqualTo("succeeded");
        assertThat(negRun.path("output").asText()).isEqualTo("走了差评分支");

        // condition 节点的 trace 记录命中分支
        String conditionTrace = negRun.path("traces").get(2).path("output").asText();
        assertThat(conditionTrace).contains("命中分支").contains("endNeg");
    }

    @Test
    void 必填变量缺失同步拒绝() throws Exception {
        postJson("/api/v1/workflows", buildSequentialBody(WF_PREFIX + "顺序"));
        long wfId = workflowIdByName(WF_PREFIX + "顺序");

        String resp = sendJsonRaw(MockMvcRequestBuilders.post("/api/v1/workflows/" + wfId + "/run"),
                "{\"inputs\":{}}");
        assertThat(parse(resp).read("$.code", Integer.class)).isEqualTo(6009);

        // 不应产生运行记录（同步拒绝不入库）
        Integer runCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_run WHERE workflow_id = " + wfId, Integer.class);
        assertThat(runCount).isZero();
    }

    @Test
    void LLM失败归一为运行失败() throws Exception {
        postJson("/api/v1/workflows", buildSequentialBody(WF_PREFIX + "顺序"));

        // prompt 渲染后含"炸" → stub 回 500
        JsonNode run = awaitRunTerminal(WF_PREFIX + "顺序", "{\"inputs\":{\"query\":\"炸\"}}");
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("errorMsg").asText()).contains("调用失败");
        // start 正常执行、llm 节点带失败轨迹
        assertThat(run.path("traces").size()).isEqualTo(2);
        assertThat(run.path("traces").get(0).path("nodeKey").asText()).isEqualTo("start");
        assertThat(run.path("traces").get(1).path("error").asText()).contains("调用失败");
    }

    @Test
    void 无命中分支即失败() throws Exception {
        postJson("/api/v1/workflows", buildBranchBody(WF_PREFIX + "分支"));

        // 既不含好评也不含差评 → judge 回"默认回复"，两条表达式均不命中且无兜底边
        JsonNode run = awaitRunTerminal(WF_PREFIX + "分支", "{\"inputs\":{\"query\":\"随便说点什么\"}}");
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("errorMsg").asText()).contains("无命中分支");
    }

    @Test
    void 删除级联清理() throws Exception {
        String resp = postJson("/api/v1/workflows", buildSequentialBody(WF_PREFIX + "清理"));
        long wfId = parse(resp).read("$.data.id", Long.class);
        awaitRunTerminal(WF_PREFIX + "清理", "{\"inputs\":{\"query\":\"你好\"}}");

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/workflows/" + wfId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 节点 / 连线 / 运行记录一并物理清理，详情 404 → 6001
        Integer nodes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_node WHERE workflow_id = " + wfId, Integer.class);
        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_edge WHERE workflow_id = " + wfId, Integer.class);
        Integer runs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_run WHERE workflow_id = " + wfId, Integer.class);
        assertThat(nodes).isZero();
        assertThat(edges).isZero();
        assertThat(runs).isZero();

        String gone = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workflows/" + wfId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(parse(gone).read("$.code", Integer.class)).isEqualTo(6001);
    }
    // ---------- 对话页直连工作流 ----------

    /** 收集思考流（工作流节点进度）与 token 的监听器 */
    private static final java.util.List<String> reasoningLog =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private ChatStreamListener loggingListener() {
        return new ChatStreamListener() {
            @Override
            public void onToken(String delta) {
            }

            @Override
            public void onReasoning(String delta) {
                reasoningLog.add(delta);
            }
        };
    }

    private long createWorkflowSession(long wfId) throws Exception {
        String resp = postJson("/api/v1/sessions", "{\"workflowId\":" + wfId + "}");
        return parse(resp).read("$.data.id", Long.class);
    }

    @Test
    void 对话直连工作流会话() throws Exception {
        postJson("/api/v1/workflows", buildSequentialBody(WF_PREFIX + "对话"));
        long wfId = workflowIdByName(WF_PREFIX + "对话");

        // 建工作流会话：agentId 为空
        String sessionResp = sendJsonRaw(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sessions"),
                "{\"workflowId\":" + wfId + "}");
        assertThat(parse(sessionResp).read("$.code", Integer.class)).isEqualTo(200);
        assertThat(parse(sessionResp).read("$.data.agentId", Object.class)).isNull();
        long sessionId = parse(sessionResp).read("$.data.id", Long.class);

        // 发消息：走工作流（节点进度推思考流，最终输出一次推送）
        reasoningLog.clear();
        ChatStreamResult result = chatService
                .streamChat(sessionId, "你好工作流对话", loggingListener())
                .get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result.isOk()).isTrue();
        assertThat(result.getContent()).isEqualTo("最终：收到：你好工作流对话");

        // 进度事件按节点顺序到达（start → llm1 → end）
        String progress = String.join("", reasoningLog);
        assertThat(progress).contains("工作流开始执行");
        assertThat(progress).contains("执行节点「入口」(start)");
        assertThat(progress).contains("执行节点「回声」(llm)");
        assertThat(progress).contains("工作流执行结束");

        // 消息落库：user + assistant
        String messages = get("/api/v1/sessions/" + sessionId + "/messages");
        assertThat(parse(messages).read("$.data.length()", Integer.class)).isEqualTo(2);
        assertThat(parse(messages).read("$.data[0].role", String.class)).isEqualTo("user");
        assertThat(parse(messages).read("$.data[1].content", String.class))
                .isEqualTo("最终：收到：你好工作流对话");

        // 运行记录落库（对话触发的运行同样可追溯，带轨迹）
        Integer runCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_run WHERE workflow_id = " + wfId, Integer.class);
        assertThat(runCount).isEqualTo(1);
    }

    @Test
    void 对话工作流入口契约校验() throws Exception {
        // start 无输入变量：用户消息无处填 → 建会话被拒 6011
        String noVars = """
                {
                  "name": "%s",
                  "enabled": 1,
                  "nodes": [
                    {"nodeKey":"start","nodeType":"start","name":"入口","config":{"variables":[]}},
                    {"nodeKey":"llm1","nodeType":"llm","name":"L",
                     "config":{"modelConfigId":%d,"prompt":"固定问题，不引用变量"}},
                    {"nodeKey":"end","nodeType":"end","name":"E","config":{"outputTemplate":"${llm1.output}"}}
                  ],
                  "edges": [
                    {"sourceNodeKey":"start","targetNodeKey":"llm1","conditionType":"always","conditionExpr":""},
                    {"sourceNodeKey":"llm1","targetNodeKey":"end","conditionType":"always","conditionExpr":""}
                  ]
                }
                """.formatted(WF_PREFIX + "无变量", chatModelId);
        postJson("/api/v1/workflows", noVars);
        long wfId = workflowIdByName(WF_PREFIX + "无变量");
        String resp = sendJsonRaw(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sessions"),
                "{\"workflowId\":" + wfId + "}");
        assertThat(parse(resp).read("$.code", Integer.class)).isEqualTo(6011);

        // agentId 与 workflowId 同传 → 4007
        String both = sendJsonRaw(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sessions"),
                "{\"workflowId\":" + wfId + ",\"agentId\":1}");
        assertThat(parse(both).read("$.code", Integer.class)).isEqualTo(4007);
    }

    @Test
    void 对话工作流失败报错不降级() throws Exception {
        postJson("/api/v1/workflows", buildSequentialBody(WF_PREFIX + "对话"));
        long wfId = workflowIdByName(WF_PREFIX + "对话");
        long sessionId = createWorkflowSession(wfId);

        // prompt 渲染后含"炸" → stub 500 → 工作流失败，对话收 error（不降级直答）
        ChatStreamResult result = chatService
                .streamChat(sessionId, "炸", loggingListener())
                .get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getErrorMessage()).contains("工作流执行失败").contains("调用失败");

        // 失败不落 assistant 消息（无内容），用户消息已落
        String messages = get("/api/v1/sessions/" + sessionId + "/messages");
        assertThat(parse(messages).read("$.data.length()", Integer.class)).isEqualTo(1);
        assertThat(parse(messages).read("$.data[0].role", String.class)).isEqualTo("user");

        // 对话链路也不会去调直答模型：stub 收到的最后一次请求仍是工作流节点那次
        assertThat(lastLlmBody.get()).contains("炸");
    }
}
