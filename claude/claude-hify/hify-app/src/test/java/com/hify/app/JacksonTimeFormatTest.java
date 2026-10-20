package com.hify.app;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.hify.common.dto.Result;

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
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Jackson 全局时间格式测试：走真实 MVC 序列化/反序列化链路。
 * 序列化：LocalDateTime -> 2026-10-17T14:30:05，LocalDate -> 2026-10-17；
 * 反序列化：同一格式的字符串能还原。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(JacksonTimeFormatTest.TestController.class)
class JacksonTimeFormatTest {

    @Autowired
    private MockMvc mockMvc;

    record TimeData(LocalDate date, LocalDateTime dateTime) {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/time")
        public Result<TimeData> time() {
            return Result.ok(new TimeData(LocalDate.of(2026, 10, 17), LocalDateTime.of(2026, 10, 17, 14, 30, 5)));
        }

        @PostMapping("/test/time")
        public Result<LocalDateTime> echo(@RequestBody TimeData req) {
            return Result.ok(req.dateTime());
        }
    }

    @Test
    void 序列化为ISO格式() throws Exception {
        mockMvc.perform(get("/test/time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-10-17"))
                .andExpect(jsonPath("$.data.dateTime").value("2026-10-17T14:30:05"));
    }

    @Test
    void 反序列化同一格式() throws Exception {
        mockMvc.perform(post("/test/time")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-10-17\",\"dateTime\":\"2026-10-17T14:30:05\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("2026-10-17T14:30:05"));
    }

    @Test
    void 带毫秒的输入被拒绝() throws Exception {
        // 统一格式不含毫秒，带毫秒的字符串应报参数错误而非静默吞掉
        mockMvc.perform(post("/test/time")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-10-17\",\"dateTime\":\"2026-10-17T14:30:05.123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1005));
    }
}
