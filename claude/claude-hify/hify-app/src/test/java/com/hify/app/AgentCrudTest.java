package com.hify.app;

import org.junit.jupiter.api.AfterEach;
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
 * Agent CRUD 端到端验证：MockMvc → Controller → Service → Mapper → MySQL。
 * 覆盖：模型绑定校验（3000 段错误码）、名称重复、联表（model_name / provider_name）、
 * 默认值（temperature 0.70 / max_tokens 0）、更新 null 保持原值、逻辑删除、分页过滤。
 * 模型配置夹具经 provider / model-configs 真实接口创建。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long chatModelId;

    private long embedModelId;

    private long disabledModelId;

    @BeforeEach
    void setup() throws Exception {
        cleanupFixtures();
        // 夹具走真实接口：一个 provider + 三个模型（chat / embedding / 停用 chat）
        String provider = postJson("/api/v1/providers",
                "{\"name\":\"Agent 测试提供商\",\"type\":\"openai_compatible\",\"baseUrl\":\"http://localhost:9/v1\"}");
        long providerId = parse(provider).read("$.data.id", Long.class);
        chatModelId = createModel(providerId, "agent-test-chat-model", "chat", 1);
        embedModelId = createModel(providerId, "agent-test-embed-model", "embedding", 1);
        disabledModelId = createModel(providerId, "agent-test-disabled-model", "chat", 0);
    }

    @AfterEach
    void teardown() {
        cleanupFixtures();
    }

    /** 只清理本测试的夹具数据（按名字精确匹配），不碰库里其他真实数据 */
    private void cleanupFixtures() {
        // 只删绑定测试模型的 Agent（本类夹具），禁止全表 DELETE——开发库存有用户真实数据
        jdbcTemplate.execute("DELETE FROM agent WHERE model_config_id IN "
                + "(SELECT id FROM model_config WHERE model_name LIKE 'agent-test-%')");
        jdbcTemplate.execute("DELETE FROM model_config WHERE model_name LIKE 'agent-test-%'");
        jdbcTemplate.execute("DELETE FROM provider WHERE name = 'Agent 测试提供商'");
    }

    private long createModel(long providerId, String name, String type, int enabled) throws Exception {
        String resp = postJson("/api/v1/model-configs", """
                {"providerId":%d,"modelName":"%s","modelType":"%s","enabled":%d}
                """.formatted(providerId, name, type, enabled));
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

    private long createAgent(String body) throws Exception {
        String resp = postJson("/api/v1/agents", body);
        return parse(resp).read("$.data.id", Long.class);
    }

    @Test
    void 创建与联表() throws Exception {
        long id = createAgent("""
                {"name":"客服助手","description":"处理售后咨询","modelConfigId":%d,
                 "systemPrompt":"你是售后客服","temperature":0.2,"maxTokens":1024}
                """.formatted(chatModelId));

        mockMvc.perform(get("/api/v1/agents/{id}", id))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("客服助手"))
                // 联表：模型名 + 提供商名（agent → provider 跨模块 Service 调用）
                .andExpect(jsonPath("$.data.modelName").value("agent-test-chat-model"))
                .andExpect(jsonPath("$.data.providerName").value("Agent 测试提供商"))
                .andExpect(jsonPath("$.data.systemPrompt").value("你是售后客服"))
                .andExpect(jsonPath("$.data.temperature").value(0.2))
                .andExpect(jsonPath("$.data.maxTokens").value(1024))
                .andExpect(jsonPath("$.data.enabled").value(1));
    }

    @Test
    void 创建默认值() throws Exception {
        long id = createAgent("{\"name\":\"裸配置\",\"modelConfigId\":%d}".formatted(chatModelId));
        mockMvc.perform(get("/api/v1/agents/{id}", id))
                .andExpect(jsonPath("$.data.temperature").value(0.70))
                .andExpect(jsonPath("$.data.maxTokens").value(0))
                .andExpect(jsonPath("$.data.systemPrompt").value(""))
                .andExpect(jsonPath("$.data.description").value(""));
    }

    @Test
    void 模型绑定校验() throws Exception {
        // 不存在的模型 → 3003
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"modelConfigId\":99999999}"))
                .andExpect(jsonPath("$.code").value(3003));
        // embedding 模型不能绑 → 3003
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"modelConfigId\":%d}".formatted(embedModelId)))
                .andExpect(jsonPath("$.code").value(3003));
        // 停用的模型不能绑 → 3003
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"modelConfigId\":%d}".formatted(disabledModelId)))
                .andExpect(jsonPath("$.code").value(3003));
        // 换绑到停用模型同样拦截
        long id = createAgent("{\"name\":\"换绑\",\"modelConfigId\":%d}".formatted(chatModelId));
        mockMvc.perform(put("/api/v1/agents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"换绑\",\"modelConfigId\":%d}".formatted(disabledModelId)))
                .andExpect(jsonPath("$.code").value(3003));
    }

    @Test
    void 参数校验() throws Exception {
        // temperature 越界 → 通用参数错误 1000
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"modelConfigId\":%d,\"temperature\":2.5}".formatted(chatModelId)))
                .andExpect(jsonPath("$.code").value(1000));
        // 缺 name / modelConfigId → 1000
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelConfigId\":%d}".formatted(chatModelId)))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(jsonPath("$.code").value(1000));
    }

    @Test
    void 名称重复() throws Exception {
        createAgent("{\"name\":\"同名\",\"modelConfigId\":%d}".formatted(chatModelId));
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"同名\",\"modelConfigId\":%d}".formatted(chatModelId)))
                .andExpect(jsonPath("$.code").value(3002));
    }

    @Test
    void 更新换绑与null保持原值() throws Exception {
        long id = createAgent("""
                {"name":"原配置","description":"旧描述","modelConfigId":%d,
                 "systemPrompt":"旧提示词","temperature":1.5,"maxTokens":2048}
                """.formatted(chatModelId));
        long newChatModelId = createModel(
                jdbcTemplate.queryForObject("SELECT id FROM provider WHERE name = 'Agent 测试提供商'", Long.class),
                "agent-test-chat-model-2", "chat", 1);

        mockMvc.perform(put("/api/v1/agents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新配置","description":"新描述","modelConfigId":%d,
                                 "systemPrompt":null,"temperature":null,"maxTokens":null}
                                """.formatted(newChatModelId)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.modelName").value("agent-test-chat-model-2"))
                .andExpect(jsonPath("$.data.description").value("新描述"))
                // null 字段保持原值
                .andExpect(jsonPath("$.data.systemPrompt").value("旧提示词"))
                .andExpect(jsonPath("$.data.temperature").value(1.5))
                .andExpect(jsonPath("$.data.maxTokens").value(2048));

        // system_prompt 传空串 = 清空
        mockMvc.perform(put("/api/v1/agents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新配置\",\"modelConfigId\":%d,\"systemPrompt\":\"\"}"
                                .formatted(newChatModelId)))
                .andExpect(jsonPath("$.data.systemPrompt").value(""));
    }

    @Test
    void 删除与不存在() throws Exception {
        long id = createAgent("{\"name\":\"待删\",\"modelConfigId\":%d}".formatted(chatModelId));
        mockMvc.perform(delete("/api/v1/agents/{id}", id))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/v1/agents/{id}", id))
                .andExpect(jsonPath("$.code").value(3001));
        // 逻辑删除：库内 deleted=1
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM agent WHERE id = " + id, Integer.class);
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    void 分页与名称过滤() throws Exception {
        // 开发库与用户真实数据共存：断言一律用「基线 + 本用例新增」
        int totalBefore = agentTotal(null);
        int helperBefore = agentTotal("助手");

        createAgent("{\"name\":\"翻译助手\",\"modelConfigId\":%d}".formatted(chatModelId));
        createAgent("{\"name\":\"代码助手\",\"modelConfigId\":%d}".formatted(chatModelId));
        createAgent("{\"name\":\"写作助手\",\"modelConfigId\":%d}".formatted(chatModelId));

        mockMvc.perform(get("/api/v1/agents").param("page", "1").param("pageSize", "10"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(totalBefore + 3))
                // 列表也带联表信息（id 倒序，最新在前 = 刚创建的）
                .andExpect(jsonPath("$.data.list[0].modelName").value("agent-test-chat-model"))
                .andExpect(jsonPath("$.data.list[0].providerName").value("Agent 测试提供商"));

        assertThat(agentTotal("助手")).isEqualTo(helperBefore + 3);
        assertThat(agentTotal("不存在")).isEqualTo(0);
    }

    private int agentTotal(String name) throws Exception {
        var request = get("/api/v1/agents").param("page", "1").param("pageSize", "1");
        if (name != null) {
            request = request.param("name", name);
        }
        return parse(mockMvc.perform(request)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .read("$.data.total", Integer.class);
    }
}
