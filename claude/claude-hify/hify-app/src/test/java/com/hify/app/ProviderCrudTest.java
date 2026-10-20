package com.hify.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import static com.jayway.jsonpath.JsonPath.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Provider CRUD 端到端验证：MockMvc → Controller → Service → Mapper → MySQL。
 * 覆盖：类型/base_url/api_key 业务校验（2000 段错误码）、名称重复、
 * api_key 掩码（明文不出服务端）、更新留空保持原 Key、逻辑删除、分页过滤。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM provider WHERE name IN "
                + "('DeepSeek','A-DeepSeek','B-Ollama','C-OpenAI','OpenAI 官方','本地 vLLM','本地 Ollama','待删')");
    }

    private long createProvider(String body) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return parse(resp).read("$.data.id", Long.class);
    }

    @Test
    void 创建与掩码() throws Exception {
        long id = createProvider("""
                {"name":"OpenAI 官方","type":"openai","apiKey":"sk-very-secret-key-ab3f","remark":"主力"}
                """);
        assertThat(id).isPositive();

        mockMvc.perform(get("/api/v1/providers/{id}", id))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("OpenAI 官方"))
                .andExpect(jsonPath("$.data.type").value("openai"))
                // base_url 留空 → 官方默认地址
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.openai.com/v1"))
                // 明文永不回传，只回掩码（尾 4 位）
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****ab3f"))
                .andExpect(jsonPath("$.data.enabled").value(1))
                .andExpect(jsonPath("$.data.remark").value("主力"))
                .andExpect(jsonPath("$.data.lastTestOk").isEmpty());   // 从未测试，null

        // 库里存的是明文（供调用时使用），接口不泄露
        String stored = jdbcTemplate.queryForObject(
                "SELECT api_key FROM provider WHERE id = " + id, String.class);
        assertThat(stored).isEqualTo("sk-very-secret-key-ab3f");
    }

    @Test
    void 类型与必填校验() throws Exception {
        // 类型不支持 → 2003
        mockMvc.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"type\":\"unknown\",\"apiKey\":\"k\"}"))
                .andExpect(jsonPath("$.code").value(2003));

        // openai_compatible 没有默认地址，base_url 必填 → 2004
        mockMvc.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"type\":\"openai_compatible\"}"))
                .andExpect(jsonPath("$.code").value(2004));

        // openai 必须有 api_key → 2005
        mockMvc.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"type\":\"openai\",\"baseUrl\":\"https://api.openai.com/v1\"}"))
                .andExpect(jsonPath("$.code").value(2005));

        // openai_compatible 无 key 合法（本地 vLLM 场景）
        createProvider("""
                {"name":"本地 vLLM","type":"openai_compatible","baseUrl":"http://localhost:8000/v1"}
                """);

        // ollama 无 key 合法，base_url 用默认
        long ollamaId = createProvider("{\"name\":\"本地 Ollama\",\"type\":\"ollama\"}");
        mockMvc.perform(get("/api/v1/providers/{id}", ollamaId))
                .andExpect(jsonPath("$.data.baseUrl").value("http://localhost:11434"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value(""));
    }

    @Test
    void 名称重复() throws Exception {
        createProvider("{\"name\":\"DeepSeek\",\"type\":\"openai_compatible\",\"baseUrl\":\"https://api.deepseek.com/v1\"}");
        mockMvc.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DeepSeek\",\"type\":\"openai_compatible\",\"baseUrl\":\"https://api.deepseek.com/v1\"}"))
                .andExpect(jsonPath("$.code").value(2002));
    }

    @Test
    void 更新留空保持原Key并沿用新类型默认地址() throws Exception {
        long id = createProvider(
                "{\"name\":\"DeepSeek\",\"type\":\"openai_compatible\",\"baseUrl\":\"https://api.deepseek.com/v1\",\"apiKey\":\"sk-ds-12345678\"}");

        // api_key 留空：改类型为 openai（有默认地址）、改备注
        mockMvc.perform(put("/api/v1/providers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DeepSeek\",\"type\":\"openai\",\"baseUrl\":\"\",\"apiKey\":\"\",\"remark\":\"改为官方\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("openai"))
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.data.remark").value("改为官方"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****5678"));   // 原 Key 未丢

        String stored = jdbcTemplate.queryForObject(
                "SELECT api_key FROM provider WHERE id = " + id, String.class);
        assertThat(stored).isEqualTo("sk-ds-12345678");   // 库里仍是原值
    }

    @Test
    void 不存在与删除() throws Exception {
        mockMvc.perform(get("/api/v1/providers/{id}", 99999))
                .andExpect(jsonPath("$.code").value(2001));

        long id = createProvider("{\"name\":\"待删\",\"type\":\"ollama\"}");
        mockMvc.perform(delete("/api/v1/providers/{id}", id))
                .andExpect(jsonPath("$.code").value(200));
        // 逻辑删除：接口不可见，库中保留
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM provider WHERE id = " + id, Integer.class);
        assertThat(deleted).isEqualTo(1);
        mockMvc.perform(get("/api/v1/providers/{id}", id))
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void 分页与类型过滤() throws Exception {
        // 开发库与用户真实数据共存：断言一律用「基线 + 本用例新增」
        int totalBefore = providerTotal(null);
        int ollamaBefore = providerTotal("ollama");

        createProvider("{\"name\":\"A-DeepSeek\",\"type\":\"openai_compatible\",\"baseUrl\":\"https://api.deepseek.com/v1\"}");
        createProvider("{\"name\":\"B-Ollama\",\"type\":\"ollama\"}");
        createProvider("{\"name\":\"C-OpenAI\",\"type\":\"openai\",\"apiKey\":\"sk-12345678\"}");

        mockMvc.perform(get("/api/v1/providers")
                        .param("page", "1").param("pageSize", "2"))
                .andExpect(jsonPath("$.data.total").value(totalBefore + 3))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(2));

        // type 过滤（id 倒序，最后创建的在前）
        mockMvc.perform(get("/api/v1/providers").param("type", "ollama"))
                .andExpect(jsonPath("$.data.total").value(ollamaBefore + 1))
                .andExpect(jsonPath("$.data.list[0].name").value("B-Ollama"));

        // name 模糊（只匹配本用例的 A-DeepSeek）
        mockMvc.perform(get("/api/v1/providers").param("name", "A-DeepSeek"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    private int providerTotal(String type) throws Exception {
        var request = get("/api/v1/providers").param("page", "1").param("pageSize", "1");
        if (type != null) {
            request = request.param("type", type);
        }
        return parse(mockMvc.perform(request)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .read("$.data.total", Integer.class);
    }
}
