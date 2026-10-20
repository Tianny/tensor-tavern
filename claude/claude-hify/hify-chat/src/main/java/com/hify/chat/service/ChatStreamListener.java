package com.hify.chat.service;

import com.hify.chat.dto.ToolCallEvent;
import com.hify.chat.dto.ToolResultEvent;

/**
 * 对话流增量回调。结束/失败不走回调，由 streamChat 返回的 future 携带
 * {@link com.hify.chat.dto.ChatStreamResult}（Controller 转成 done / error SSE 事件）。
 */
public interface ChatStreamListener {

    /**
     * 收到一段正文增量。抛异常（如客户端已断开导致 SseEmitter.send 失败）
     * 会中止上游读取，已生成内容照常落库。
     */
    void onToken(String delta);

    /**
     * 收到一段思考流增量（qwen 的 reasoning_content / claude 的 thinking）。
     * 思考流只透传给前端展示，不落库、不进上下文。
     */
    default void onReasoning(String delta) {
        // 默认忽略：测试与非思考场景不需要
    }

    /**
     * 模型决定调用某个 MCP 工具（function calling 的工具决策轮）。
     * 抛异常同样中止上游（客户端断开）。
     */
    default void onToolCall(ToolCallEvent event) {
        // 默认忽略：无工具绑定时不会触发
    }

    /** 工具执行完成（ok=false 表示未知工具等本地失败；服务端错误走整体报错） */
    default void onToolResult(ToolResultEvent event) {
        // 默认忽略
    }
}
