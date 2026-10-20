package com.hify.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.jayway.jsonpath.JsonPath.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 端到端：知识库 CRUD → 文档上传 → 异步管线（分块 / embedding stub / pgvector 落库）
 * → 检索测试接口 → Agent 绑定 → 对话注入参考资料 → 检索失败降级直答 → 删除清理。
 *
 * <p>embedding stub 是关键词→维度的伪向量（Hify→0、部署→1、MySQL→2、天气→3、基线→1023），
 * 余弦相似度行为等同关键词重合度，检索语义可控可断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeRagTest {

    private static final String KB_PREFIX = "rag测试库-";

    /** chat stub 收到最近一次请求体（断言 system prompt 注入） */
    private static final AtomicReference<String> lastChatBody = new AtomicReference<>("");

    private static HttpServer stubServer;

    private static int stubPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;   // MySQL 主库

    /** 测试内自建 PG 连接（同 hify.pgvector.* 配置），避免暴露 Bean 干扰主库装配 */
    @Autowired
    private org.springframework.core.env.Environment environment;

    private JdbcTemplate pgJdbc;

    private JdbcTemplate pgJdbcTemplate() {
        if (pgJdbc == null) {
            com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
            ds.setJdbcUrl(environment.getProperty("hify.pgvector.url"));
            ds.setUsername(environment.getProperty("hify.pgvector.username"));
            ds.setPassword(environment.getProperty("hify.pgvector.password"));
            ds.setMaximumPoolSize(2);
            pgJdbc = new JdbcTemplate(ds);
        }
        return pgJdbc;
    }

    @Autowired
    private ChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    private long embeddingModelId;

    private long chatModelId;

    private long agentId;

    @BeforeAll
    static void startStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubServer.createContext("/embeddings", KnowledgeRagTest::handleEmbeddings);
        stubServer.createContext("/v1/chat/completions", KnowledgeRagTest::handleChat);
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        stubServer.stop(0);
    }

    /** 伪 embedding：关键词命中维度置 1（基线维度恒 1 防零向量），未命中文本间余弦为 0 */
    private static void handleEmbeddings(HttpExchange exchange) throws IOException {
        if (!"Bearer sk-emb-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        com.fasterxml.jackson.databind.node.JsonNodeFactory f =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
        List<String> inputs = new ArrayList<>();
        body.path("input").forEach(node -> inputs.add(node.asText()));

        ObjectNode resp = new ObjectMapper().createObjectNode();
        resp.put("object", "list");
        resp.put("model", body.path("model").asText());
        ArrayNode data = resp.putArray("data");
        for (int i = 0; i < inputs.size(); i++) {
            String text = inputs.get(i).toLowerCase();
            ObjectNode item = data.addObject();
            item.put("object", "embedding");
            item.put("index", i);
            ArrayNode vector = item.putArray("embedding");
            for (int d = 0; d < 1024; d++) {
                vector.add(0.0f);
            }
            boolean anyKeyword = false;
            if (text.contains("hify")) {
                vector.set(0, f.numberNode(1.0f));
                anyKeyword = true;
            }
            if (text.contains("部署")) {
                vector.set(1, f.numberNode(1.0f));
                anyKeyword = true;
            }
            if (text.contains("mysql")) {
                vector.set(2, f.numberNode(1.0f));
                anyKeyword = true;
            }
            if (text.contains("天气")) {
                vector.set(3, f.numberNode(1.0f));
                anyKeyword = true;
            }
            if (!anyKeyword) {
                // 无关键词文本给一个哈希维度：非零（避开零向量）且与任何文本都不相关
                vector.set(500 + Math.floorMod(text.hashCode(), 500), f.numberNode(1.0f));
            }
        }
        byte[] bytes = new ObjectMapper().writeValueAsBytes(resp);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** chat 端点：记录请求体（供断言 system prompt），回放固定流 */
    private static void handleChat(HttpExchange exchange) throws IOException {
        if (!"Bearer sk-chat-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, " unauthorized");
            return;
        }
        lastChatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String line : List.of(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"根据资料回答\"}}]}",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"[1]\"}}]}",
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4}}",
                    "data: [DONE]")) {
                out.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
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
        long embProvider = createProvider("rag-stub-embedding",
                "{\"name\":\"rag-stub-embedding\",\"type\":\"openai_compatible\","
                        + "\"baseUrl\":\"http://localhost:%d\",\"apiKey\":\"sk-emb-key\"}");
        embeddingModelId = createModel(embProvider, "rag-test-emb", "embedding");
        long chatProvider = createProvider("rag-stub-chat",
                "{\"name\":\"rag-stub-chat\",\"type\":\"openai_compatible\","
                        + "\"baseUrl\":\"http://localhost:%d/v1\",\"apiKey\":\"sk-chat-key\"}");
        chatModelId = createModel(chatProvider, "rag-test-chat", "chat");
        agentId = createAgent();
    }

    @AfterEach
    void cleanup() throws Exception {
        cleanupFixtures();
    }

    /** 只删测试夹具（精确名字 / LIKE 前缀），用户真实数据（bailian / 天气助手等）不动 */
    private void cleanupFixtures() throws Exception {
        // 1. pg 向量：按测试知识库 id 清
        List<Long> kbIds = jdbcTemplate.queryForList(
                "SELECT id FROM knowledge_base WHERE name LIKE '" + KB_PREFIX + "%'", Long.class);
        if (!kbIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(kbIds.size(), "?"));
            pgJdbcTemplate().update("DELETE FROM document_chunk WHERE knowledge_base_id IN (" + placeholders + ")",
                    kbIds.toArray());
        }
        // 2. 磁盘原始文件（先取文档 id 再删行）
        List<Long> docIds = jdbcTemplate.queryForList(
                "SELECT id FROM document WHERE knowledge_base_id IN (SELECT id FROM knowledge_base WHERE name LIKE '"
                        + KB_PREFIX + "%')", Long.class);
        for (Long docId : docIds) {
            Files.deleteIfExists(Path.of("data/knowledge", docId + ".txt"));
            Files.deleteIfExists(Path.of("data/knowledge", docId + ".md"));
        }
        // 3. MySQL：文档 / 绑定 / 库 / 会话消息 / Agent / 模型 / 提供商（物理删，夹具无需追溯）
        jdbcTemplate.execute("DELETE FROM document WHERE knowledge_base_id IN "
                + "(SELECT id FROM knowledge_base WHERE name LIKE '" + KB_PREFIX + "%')");
        jdbcTemplate.execute("DELETE FROM agent_knowledge WHERE agent_id = " + agentId
                + " OR knowledge_base_id IN (SELECT id FROM knowledge_base WHERE name LIKE '" + KB_PREFIX + "%')");
        jdbcTemplate.execute("DELETE FROM knowledge_base WHERE name LIKE '" + KB_PREFIX + "%'");
        jdbcTemplate.execute("DELETE FROM chat_message WHERE session_id IN "
                + "(SELECT id FROM chat_session WHERE agent_id = " + agentId + ")");
        jdbcTemplate.execute("DELETE FROM chat_session WHERE agent_id = " + agentId);
        jdbcTemplate.execute("DELETE FROM agent WHERE name LIKE 'RAG测试Agent%'");
        jdbcTemplate.execute("DELETE FROM model_config WHERE model_name LIKE 'rag-test-%'");
        jdbcTemplate.execute("DELETE FROM provider WHERE name LIKE 'rag-stub-%'");
    }

    // ---------- 夹具 ----------

    private long createProvider(String name, String body) throws Exception {
        String resp = postJson("/api/v1/providers", body.formatted(stubPort));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createModel(long providerId, String name, String modelType) throws Exception {
        String resp = postJson("/api/v1/model-configs", """
                {"providerId":%d,"modelName":"%s","modelType":"%s","maxContextTokens":32000}
                """.formatted(providerId, name, modelType));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createAgent() throws Exception {
        String resp = postJson("/api/v1/agents", """
                {"name":"RAG测试Agent","modelConfigId":%d,"systemPrompt":"你是RAG测试助手","temperature":0.3,"maxTokens":100}
                """.formatted(chatModelId));
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createKb(String name) throws Exception {
        String resp = postJson("/api/v1/knowledge-bases",
                "{\"name\":\"%s\",\"description\":\"RAG 测试\",\"embeddingModelId\":%d}"
                        .formatted(name, embeddingModelId));
        return parse(resp).read("$.data.id", Long.class);
    }

    private String postJson(String url, String body) throws Exception {
        return sendJson(MockMvcRequestBuilders.post(url), body);
    }

    private String putJson(String url, String body) throws Exception {
        return sendJson(MockMvcRequestBuilders.put(url), body);
    }

    /** JSON 请求体一律传 UTF-8 字节：MockMvc 的 content(String) 默认按 ISO-8859-1 编码，中文会变问号 */
    private String sendJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String body) throws Exception {
        return mockMvc.perform(builder
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 不校验 code 的 JSON POST（用于断言错误码的请求） */
    private String postJsonRaw(String url, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 生成一篇多句测试文档：每个句子含可检索关键词，句长足以触发多分块与重叠 */
    private String buildDocText(int sentenceCount) {
        return IntStream.rangeClosed(1, sentenceCount)
                .mapToObj(i -> "第" + i + "句：Hify 平台的部署说明，包含 MySQL 与 Redis 的配置要点。")
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private long uploadDoc(long kbId, String fileName, String text) throws Exception {
        String resp = mockMvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents", kbId)
                        .file(new MockMultipartFile("file", fileName, "text/plain",
                                text.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return parse(resp).read("$.data.id", Long.class);
    }

    /** 轮询文档状态直到 completed（异步管线在 asyncExecutor 上跑） */
    private String awaitCompleted(long kbId, long docId) throws Exception {
        for (int i = 0; i < 50; i++) {
            String resp = mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get("/api/v1/knowledge-bases/{kbId}/documents", kbId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            for (Object doc : parse(resp).read("$.data", List.class)) {
                if (((Number) parse(doc).read("$.id")).longValue() == docId) {
                    String status = parse(doc).read("$.status", String.class);
                    if ("completed".equals(status) || "failed".equals(status)) {
                        return status;
                    }
                }
            }
            Thread.sleep(200);
        }
        return "timeout";
    }

    // ---------- 用例 ----------

    @Test
    void 知识库CRUD与向量化模型校验() throws Exception {
        // chat 模型建库 → 7003
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s错\",\"embeddingModelId\":%d}"
                                .formatted(KB_PREFIX, chatModelId)).getBytes(StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.code").value(7003));

        long kbId = createKb(KB_PREFIX + "主库");
        // 联表模型 / 提供商名
        String list = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/knowledge-bases"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(list).contains(KB_PREFIX + "主库").contains("rag-stub-embedding");

        // 重名 → 7002
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"%s主库\",\"embeddingModelId\":%d}"
                                .formatted(KB_PREFIX, embeddingModelId)).getBytes(StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.code").value(7002));

        // 改名 OK；改后列表生效
        putJson("/api/v1/knowledge-bases/" + kbId,
                "{\"name\":\"%s主库改\",\"description\":\"改描述\"}".formatted(KB_PREFIX));
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/knowledge-bases"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).contains(KB_PREFIX + "主库改");

        // 删除后不可达
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/knowledge-bases/" + kbId))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/knowledge-bases/" + kbId + "/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"any\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.code").value(7001));
    }

    @Test
    void 文档上传分块与向量化管线() throws Exception {
        long kbId = createKb(KB_PREFIX + "文档库");
        // 不支持的类型 → 7005
        mockMvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents", kbId)
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", "pdf".getBytes())))
                .andExpect(jsonPath("$.code").value(7005));

        // 上传即 pending，随后管线完成（30 句 × 约 25 token ≈ 750 token → 多块 + 重叠）
        long docId = uploadDoc(kbId, "rag测试文档.txt", buildDocText(30));
        String status = awaitCompleted(kbId, docId);
        assertThat(status).isEqualTo("completed");

        String list = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/knowledge-bases/{kbId}/documents", kbId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(list).contains("\"chunkCount\":");
        int chunkCount = ((Number) parse(list).read("$.data[0].chunkCount")).intValue();
        assertThat(chunkCount).isGreaterThanOrEqualTo(2);

        // 分块预览：chunk0 内容完整；chunk1 开头来自 chunk0 尾部（重叠生效）
        String chunks = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/documents/{id}/chunks", docId)
                        .param("limit", "50"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<?> chunkList = parse(chunks).read("$.data", List.class);
        assertThat(chunkList.size()).isEqualTo(chunkCount);
        String first = parse(chunkList.get(0)).read("$.content", String.class);
        String second = parse(chunkList.get(1)).read("$.content", String.class);
        assertThat(first).contains("第1句");
        // 重叠：第二块的开头句（"第N句"）在第一块里出现过
        String secondFirstSentence = second.substring(0, second.indexOf("："));
        assertThat(first).contains(secondFirstSentence);

        // pg 真有向量行
        Integer rows = pgJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM document_chunk WHERE document_id = " + docId, Integer.class);
        assertThat(rows).isEqualTo(chunkCount);

        // md 也能走通
        long mdId = uploadDoc(kbId, "说明.md", "# Hify 部署\n\nMySQL 配置。");
        assertThat(awaitCompleted(kbId, mdId)).isEqualTo("completed");
    }

    @Test
    void 检索命中与无关查询过滤() throws Exception {
        long kbId = createKb(KB_PREFIX + "检索库");
        long docId = uploadDoc(kbId, "检索文档.txt", buildDocText(5));
        assertThat(awaitCompleted(kbId, docId)).isEqualTo("completed");

        // 相关查询命中
        String hit = postJsonRaw("/api/v1/knowledge-bases/" + kbId + "/search", "{\"query\":\"Hify 怎么部署\",\"topK\":3}");
        List<?> hits = parse(hit).read("$.data", List.class);
        assertThat(hits).isNotEmpty();
        assertThat(parse(hits.get(0)).read("$.content", String.class)).contains("部署");
        assertThat(parse(hits.get(0)).read("$.documentName", String.class)).isEqualTo("检索文档.txt");
        assertThat(parse(hits.get(0)).read("$.rank", Integer.class)).isEqualTo(1);

        // 无关键词查询：余弦 0 低于阈值 → 过滤为空
        String miss = postJsonRaw("/api/v1/knowledge-bases/" + kbId + "/search", "{\"query\":\"量子纠缠的研究进展\"}");
        assertThat((List<?>) parse(miss).read("$.data", List.class)).isEmpty();
    }

    @Test
    void RAG注入对话与未绑定直答() throws Exception {
        long kbId = createKb(KB_PREFIX + "对话库");
        long docId = uploadDoc(kbId, "对话文档.txt", buildDocText(3));
        assertThat(awaitCompleted(kbId, docId)).isEqualTo("completed");
        long sessionId = createSession();

        // 未绑定：system prompt 是 Agent 原文
        chatService.streamChat(sessionId, "Hify 怎么部署", plainListener()).get(15, TimeUnit.SECONDS);
        JsonNode system = objectMapper.readTree(lastChatBody.get()).path("messages").get(0);
        assertThat(system.path("content").asText()).isEqualTo("你是RAG测试助手");

        // 绑定后：注入参考资料
        putJson("/api/v1/agents/" + agentId + "/knowledge-bases",
                "{\"knowledgeBaseIds\":[" + kbId + "]}");
        long sessionId2 = createSession();
        ChatStreamResult result = chatService.streamChat(sessionId2, "Hify 怎么部署", plainListener())
                .get(15, TimeUnit.SECONDS);
        assertThat(result.isOk()).isTrue();
        assertThat(result.getContent()).isEqualTo("根据资料回答[1]");

        JsonNode injected = objectMapper.readTree(lastChatBody.get()).path("messages").get(0);
        String content = injected.path("content").asText();
        assertThat(content).startsWith("你是RAG测试助手");
        assertThat(content).contains("知识库资料").contains("【1】").contains("第1句").contains("回答要求");
    }

    @Test
    void 检索失败降级直答() throws Exception {
        // 建一个 embedding 指向死端口的库并绑定（Provider enabled=1 但连不上）
        long deadProvider = createProvider("rag-stub-dead",
                "{\"name\":\"rag-stub-dead\",\"type\":\"openai_compatible\","
                        + "\"baseUrl\":\"http://localhost:9\",\"apiKey\":\"sk-dead\"}");
        long deadModel = createModel(deadProvider, "rag-test-dead-emb", "embedding");
        String deadKbResp = postJson("/api/v1/knowledge-bases",
                "{\"name\":\"%s死库\",\"embeddingModelId\":%d}".formatted(KB_PREFIX, deadModel));
        long deadKbId = parse(deadKbResp).read("$.data.id", Long.class);
        putJson("/api/v1/agents/" + agentId + "/knowledge-bases",
                "{\"knowledgeBaseIds\":[" + deadKbId + "]}");

        long sessionId = createSession();
        ChatStreamResult result = chatService.streamChat(sessionId, "Hify 怎么部署", plainListener())
                .get(20, TimeUnit.SECONDS);

        // 检索抛错被吞，对话照常直答
        assertThat(result.isOk()).isTrue();
        JsonNode system = objectMapper.readTree(lastChatBody.get()).path("messages").get(0);
        assertThat(system.path("content").asText()).isEqualTo("你是RAG测试助手");
    }

    @Test
    void 文档删除清理向量与文件() throws Exception {
        long kbId = createKb(KB_PREFIX + "删除库");
        long docId = uploadDoc(kbId, "删除文档.txt", buildDocText(2));
        assertThat(awaitCompleted(kbId, docId)).isEqualTo("completed");
        Path file = Path.of("data/knowledge", docId + ".txt");
        assertThat(Files.exists(file)).isTrue();

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/documents/" + docId))
                .andExpect(jsonPath("$.code").value(200));

        Integer rows = pgJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM document_chunk WHERE document_id = " + docId, Integer.class);
        assertThat(rows).isZero();
        assertThat(Files.exists(file)).isFalse();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents/" + docId + "/chunks"))
                .andExpect(jsonPath("$.code").value(7004));
    }

    // ---------- 内部 ----------

    private long createSession() throws Exception {
        String resp = postJson("/api/v1/sessions", "{\"agentId\":" + agentId + "}");
        return parse(resp).read("$.data.id", Long.class);
    }

    private ChatStreamListener plainListener() {
        return new ChatStreamListener() {
            @Override
            public void onToken(String delta) {
            }
        };
    }
}
