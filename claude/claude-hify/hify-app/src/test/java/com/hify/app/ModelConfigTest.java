package com.hify.app;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 模型配置端到端：CRUD、校验（2006-2009 错误码）、分页过滤，
 * 以及远端模型列表拉取（各家响应字段差异 + gemini 前缀剥离 + 失败转 2008）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelConfigTest {

    @BeforeAll
    static void disableKeepAlive() {
        System.setProperty("http.keepAlive", "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static HttpServer server;

    private static final AtomicReference<String> responseBody =
            new AtomicReference<>("{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}");

    private static final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);

    private static int serverPort;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", (HttpExchange exchange) -> {
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
    void cleanup() {
        // 只删本类夹具：模型按其 P-{13位时间戳} 提供商归属删除，禁止全表 DELETE——开发库存有用户真实数据
        cleanupFixtures();
        responseBody.set("{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}");
        responseStatus.set(200);
    }

    @AfterEach
    void cleanupAfter() {
        // @BeforeEach 只能清"上一轮"，最后一个用例的夹具必须靠 AfterEach 收尾，
        // 否则 P-* 桩提供商会带着 embedding 模型残留在库里（用户会在知识库下拉里选到它）
        cleanupFixtures();
    }

    private void cleanupFixtures() {
        jdbcTemplate.execute("DELETE FROM model_config WHERE provider_id IN "
                + "(SELECT id FROM provider WHERE name REGEXP '^P-[0-9]{10,}$')");
        jdbcTemplate.execute("DELETE FROM provider WHERE name REGEXP '^P-[0-9]{10,}$'");
    }

    private long createProvider(String type) throws Exception {
        String body = """
                {"name":"P-%d","type":"%s","baseUrl":"http://127.0.0.1:%d","apiKey":"sk-test"}
                """.formatted(System.nanoTime(), type, serverPort);
        String resp = mockMvc.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return parse(resp).read("$.data.id", Long.class);
    }

    private long createModel(long providerId, String modelName, String modelType) throws Exception {
        String body = """
                {"providerId":%d,"modelName":"%s","modelType":"%s","maxContextTokens":128000}
                """.formatted(providerId, modelName, modelType);
        String resp = mockMvc.perform(post("/api/v1/model-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return parse(resp).read("$.data.id", Long.class);
    }

    @Test
    void CRUD全流程() throws Exception {
        long providerId = createProvider("openai_compatible");
        long id = createModel(providerId, "gpt-4o", "chat");

        // 创建：displayName 留空取 modelName，默认值填充
        mockMvc.perform(get("/api/v1/model-configs").param("providerId", String.valueOf(providerId)))
                .andExpect(jsonPath("$.data.list[0].displayName").value("gpt-4o"))
                .andExpect(jsonPath("$.data.list[0].modelType").value("chat"))
                .andExpect(jsonPath("$.data.list[0].maxContextTokens").value(128000))
                .andExpect(jsonPath("$.data.list[0].enabled").value(1));

        // 更新
        mockMvc.perform(put("/api/v1/model-configs/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelName":"gpt-4o","displayName":"GPT-4o","modelType":"chat",
                                 "maxContextTokens":256000,"enabled":0}
                                """))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.displayName").value("GPT-4o"))
                .andExpect(jsonPath("$.data.maxContextTokens").value(256000))
                .andExpect(jsonPath("$.data.enabled").value(0));

        // 分页 + modelType 过滤
        createModel(providerId, "text-embedding-3-small", "embedding");
        mockMvc.perform(get("/api/v1/model-configs")
                        .param("providerId", String.valueOf(providerId)).param("modelType", "chat"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].modelName").value("gpt-4o"));

        // 删除：逻辑删除
        mockMvc.perform(delete("/api/v1/model-configs/{id}", id))
                .andExpect(jsonPath("$.code").value(200));
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM model_config WHERE id = " + id, Integer.class);
        assertThat(deleted).isEqualTo(1);
        mockMvc.perform(put("/api/v1/model-configs/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"x\",\"modelType\":\"chat\"}"))
                .andExpect(jsonPath("$.code").value(2006));   // 已删除不可更新
    }

    @Test
    void 校验与重名() throws Exception {
        long providerId = createProvider("openai_compatible");

        // modelType 非法 → 2009
        mockMvc.perform(post("/api/v1/model-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":%d,\"modelName\":\"m\",\"modelType\":\"rerank\"}"
                                .formatted(providerId)))
                .andExpect(jsonPath("$.code").value(2009));

        // provider 不存在 → 2001
        mockMvc.perform(post("/api/v1/model-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":999999,\"modelName\":\"m\",\"modelType\":\"chat\"}"))
                .andExpect(jsonPath("$.code").value(2001));

        // 同一 provider 下重名 → 2007；不同 provider 同名合法
        createModel(providerId, "deepseek-chat", "chat");
        mockMvc.perform(post("/api/v1/model-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":%d,\"modelName\":\"deepseek-chat\",\"modelType\":\"chat\"}"
                                .formatted(providerId)))
                .andExpect(jsonPath("$.code").value(2007));
        long otherProvider = createProvider("openai_compatible");
        createModel(otherProvider, "deepseek-chat", "chat");
    }

    @Test
    void 拉取远端模型_openai格式() throws Exception {
        long providerId = createProvider("openai_compatible");
        responseBody.set("{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"gpt-4o\"}]}");

        mockMvc.perform(get("/api/v1/providers/{id}/remote-models", providerId))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0]").value("gpt-4o"))    // 排序保证稳定
                .andExpect(jsonPath("$.data[1]").value("gpt-4o-mini"));
    }

    @Test
    void 拉取远端模型_gemini前缀剥离() throws Exception {
        long providerId = createProvider("gemini");
        responseBody.set("{\"models\":[{\"name\":\"models/gemini-2.5-pro\"},{\"name\":\"models/gemini-2.5-flash\"}]}");

        mockMvc.perform(get("/api/v1/providers/{id}/remote-models", providerId))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0]").value("gemini-2.5-flash"))
                .andExpect(jsonPath("$.data[1]").value("gemini-2.5-pro"));
    }

    @Test
    void 拉取远端模型失败转2008() throws Exception {
        responseStatus.set(401);
        responseBody.set("{\"error\":\"bad key\"}");
        long providerId = createProvider("openai_compatible");

        mockMvc.perform(get("/api/v1/providers/{id}/remote-models", providerId))
                .andExpect(jsonPath("$.code").value(2008))
                .andExpect(jsonPath("$.message").value("拉取模型列表失败：认证失败，请检查 API Key"));
    }
}
