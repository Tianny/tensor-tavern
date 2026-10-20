package com.hify.common.trace;

import java.util.Map;

import org.springframework.core.task.TaskDecorator;
import org.slf4j.MDC;

/**
 * MDC 上下文透传装饰器：提交任务时快照 MDC（traceId 等），执行线程上恢复，
 * 执行完还原该线程原有上下文。
 *
 * <p>对话流跑在 llmExecutor 上（ChatController 提交 CompletableFuture），线程池
 * 线程与请求线程不是同一个——没有这个装饰器，LLM 调用 / 工具调用 / 落库日志
 * 会全部丢失 traceId，链路断在提交那一刻。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 捕获发生在调用线程（提交时刻，MDC 已就位）
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
