package com.hify.common.config;

import java.util.concurrent.ThreadPoolExecutor;

import com.hify.common.trace.MdcTaskDecorator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 全局线程池配置。
 * 业务代码禁止 new Thread()：LLM 调用一律提交到 llmExecutor，
 * 日志异步写入等非关键任务提交到 asyncExecutor，注入时用 @Qualifier 指定。
 * 参数默认值写在 @Value 里，可通过 application.yml 的 hify.executor.* 覆盖。
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * LLM 调用线程池：与业务请求线程隔离，流式响应（SseEmitter）也走这里。
     * 拒绝策略 CallerRunsPolicy：队列满时由调用线程自己执行，形成背压，不丢任务。
     */
    @Bean("llmExecutor")
    public ThreadPoolTaskExecutor llmExecutor(
            @Value("${hify.executor.llm.core-pool-size:10}") int corePoolSize,
            @Value("${hify.executor.llm.max-pool-size:50}") int maxPoolSize,
            @Value("${hify.executor.llm.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("llm-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // MDC（traceId）透传到池内线程：对话流的 LLM/工具调用日志才能和请求线程共用 traceId
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 优雅停机：等在途 LLM 调用跑完（最长 30s），避免流式响应被拦腰截断
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 非关键任务线程池：对话日志异步写入等，丢了不影响主流程。
     * 拒绝策略默认 AbortPolicy：池满直接抛异常，由调用方决定降级方式（不应阻塞业务线程）。
     */
    @Bean("asyncExecutor")
    public ThreadPoolTaskExecutor asyncExecutor(
            @Value("${hify.executor.async.core-pool-size:5}") int corePoolSize,
            @Value("${hify.executor.async.max-pool-size:20}") int maxPoolSize,
            @Value("${hify.executor.async.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
