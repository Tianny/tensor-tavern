package com.hify.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootTest
class ThreadPoolConfigTest {

    @Autowired
    @Qualifier("llmExecutor")
    private ThreadPoolTaskExecutor llmExecutor;

    @Autowired
    @Qualifier("asyncExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @Test
    void llmExecutor参数与拒绝策略() {
        assertThat(llmExecutor.getCorePoolSize()).isEqualTo(10);
        assertThat(llmExecutor.getMaxPoolSize()).isEqualTo(50);
        assertThat(llmExecutor.getQueueCapacity()).isEqualTo(100);
        assertThat(llmExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    void asyncExecutor参数() {
        assertThat(asyncExecutor.getCorePoolSize()).isEqualTo(5);
        assertThat(asyncExecutor.getMaxPoolSize()).isEqualTo(20);
        assertThat(asyncExecutor.getQueueCapacity()).isEqualTo(200);
    }

    @Test
    void 任务在对应前缀的线程上执行() throws Exception {
        Future<String> llmThreadName = llmExecutor.submit(() -> Thread.currentThread().getName());
        Future<String> asyncThreadName = asyncExecutor.submit(() -> Thread.currentThread().getName());
        assertThat(llmThreadName.get()).startsWith("llm-");
        assertThat(asyncThreadName.get()).startsWith("async-");
    }
}
