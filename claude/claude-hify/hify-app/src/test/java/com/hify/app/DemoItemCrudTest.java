package com.hify.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static com.jayway.jsonpath.JsonPath.parse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD 标准流程端到端验证：MockMvc → Controller → Service → Mapper → MySQL。
 * 覆盖统一响应、分页、@Valid 校验、中文校验消息、ErrorCode 异常、逻辑删除、时间自动填充。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoItemCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        // 物理清空，逻辑删除的残留行也一并清掉
        jdbcTemplate.execute("DELETE FROM demo_item");
    }

    private long createItem(String name, int status) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/demo-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"status\":%d}".formatted(name, status)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return parse(result.getResponse().getContentAsString()).read("$.data", Long.class);
    }

    @Test
    void 完整CRUD流程() throws Exception {
        // 创建
        long id = createItem("演示条目", 1);

        // 详情：字段 + ISO 时间格式
        mockMvc.perform(get("/api/v1/demo-items/{id}", id))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.name").value("演示条目"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

        // 更新
        mockMvc.perform(put("/api/v1/demo-items/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名条目\",\"status\":0}"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/v1/demo-items/{id}", id))
                .andExpect(jsonPath("$.data.name").value("改名条目"))
                .andExpect(jsonPath("$.data.status").value(0));

        // 列表（分页 + 模糊过滤）
        createItem("其他条目A", 0);
        createItem("其他条目B", 0);
        mockMvc.perform(get("/api/v1/demo-items")
                        .param("page", "1").param("pageSize", "2").param("name", "条目"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2));

        // 删除
        mockMvc.perform(delete("/api/v1/demo-items/{id}", id))
                .andExpect(jsonPath("$.code").value(200));

        // 删除后：接口报 NOT_FOUND
        mockMvc.perform(get("/api/v1/demo-items/{id}", id))
                .andExpect(jsonPath("$.code").value(1002));

        // 数据库中行还在，deleted = 1（逻辑删除而非物理删除）
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM demo_item WHERE id = ?", Integer.class, id);
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    void 名称重复被拒绝() throws Exception {
        createItem("重复名", 0);
        mockMvc.perform(post("/api/v1/demo-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重复名\",\"status\":0}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("名称已存在：重复名"));
    }

    @Test
    void 参数校验_中文名称和数值边界() throws Exception {
        // name 为空
        mockMvc.perform(post("/api/v1/demo-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"status\":0}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("参数错误: name 不能为空"));

        // status 超上限
        mockMvc.perform(post("/api/v1/demo-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"status\":5}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("参数错误: status 不能大于1"));
    }

    @Test
    void 分页参数钳制() throws Exception {
        createItem("仅一条", 0);
        // pageSize 超上限 100 被截到 100；不合法的 page 归到 1
        mockMvc.perform(get("/api/v1/demo-items").param("page", "0").param("pageSize", "999"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(100));
    }
}
