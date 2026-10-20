package com.hify.app;

import com.hify.common.dto.Result;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器覆盖测试：参数类错误必须落到 PARAM_ERROR 段，
 * 不能误报 SYSTEM_ERROR。通过内嵌的测试 Controller 走真实的 MVC 链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    /** 测试专用 Controller：只在测试上下文中注册 */
    @RestController
    @Validated
    static class TestController {

        record EchoReq(@NotBlank String name) {
        }

        @PostMapping("/test/echo")
        public Result<String> echo(@Valid @RequestBody EchoReq req) {
            return Result.ok(req.name());
        }

        @GetMapping("/test/page")
        public Result<String> page(@RequestParam Long page) {
            return Result.ok(String.valueOf(page));
        }

        @GetMapping("/test/blank")
        public Result<String> blank(@RequestParam @NotBlank String name) {
            return Result.ok(name);
        }
    }

    @Test
    void body字段校验失败() throws Exception {
        mockMvc.perform(post("/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("参数错误: name 不能为空"));
    }

    @Test
    void 单个query参数校验失败() throws Exception {
        mockMvc.perform(get("/test/blank").param("name", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("参数错误: name 不能为空"));
    }

    @Test
    void query参数类型不匹配() throws Exception {
        mockMvc.perform(get("/test/page").param("page", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("参数错误: page 应为 Long 类型"));
    }

    @Test
    void 必填query参数缺失() throws Exception {
        mockMvc.perform(get("/test/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("参数错误: 缺少必填参数 page"));
    }

    @Test
    void 请求体JSON格式错误() throws Exception {
        mockMvc.perform(post("/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-a-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    @Test
    void 正常请求不受影响() throws Exception {
        mockMvc.perform(post("/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hify\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("hify"));
    }
}
