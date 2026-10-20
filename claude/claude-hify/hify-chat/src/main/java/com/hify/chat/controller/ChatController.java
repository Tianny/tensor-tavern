package com.hify.chat.controller;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.chat.dto.ChatMessageResp;
import com.hify.chat.dto.ChatSendReq;
import com.hify.chat.dto.ChatSessionCreateReq;
import com.hify.chat.dto.ChatSessionResp;
import com.hify.chat.dto.ChatStreamResult;
import com.hify.chat.dto.ToolCallEvent;
import com.hify.chat.dto.ToolResultEvent;
import com.hify.chat.service.ChatService;
import com.hify.chat.service.ChatStreamListener;
import com.hify.common.dto.Result;
import com.hify.common.exception.CommonErrorCode;
import com.hify.common.exception.BizException;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 对话接口。发消息返回 SSE 流（token / reasoning / tool_call / tool_result / done / error
 * 事件，载荷均为单行 JSON），
 * 同步段校验失败（会话不存在等）尚未切换成流，走全局异常处理回 Result JSON——
 * 前端 fetch 按 Content-Type 区分两种响应。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    private final ObjectMapper objectMapper;

    /** 单条回复的 SSE 总时长上限（不是 chunk 间隔超时，那个在 LlmHttpClient stream-read-timeout） */
    @Value("${hify.chat.sse-timeout:5m}")
    private Duration sseTimeout;

    @PostMapping("/sessions")
    public Result<ChatSessionResp> createSession(@Valid @RequestBody ChatSessionCreateReq req) {
        return Result.ok(chatService.createSession(req));
    }

    /** 按 agent 或 workflow 过滤（二选一），最后活跃倒序 */
    @GetMapping("/sessions")
    public Result<List<ChatSessionResp>> listSessions(
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Long workflowId) {
        return Result.ok(chatService.listSessions(agentId, workflowId));
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        chatService.deleteSession(id);
        return Result.ok();
    }

    /** 消息游标分页：before 为空取最新一页，返回时间正序 */
    @GetMapping("/sessions/{id}/messages")
    public Result<List<ChatMessageResp>> listMessages(
            @PathVariable Long id,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Integer limit) {
        return Result.ok(chatService.listMessages(id, before, limit));
    }

    /** 发消息，返回 SSE 流 */
    @PostMapping("/sessions/{id}/messages")
    public SseEmitter sendMessage(@PathVariable Long id, @Valid @RequestBody ChatSendReq req) {
        // 请求链路起点（此后 llmExecutor 上的 LLM/工具调用日志共用同一 traceId）
        log.info("对话请求进入 sessionId={} 内容长度={}", id, req.getContent().length());
        SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());

        // token / reasoning / tool_call / tool_result 事件：客户端断开时 send 抛异常
        // → 中止上游流（已生成部分照常落库）
        ChatStreamListener listener = new ChatStreamListener() {
            @Override
            public void onToken(String delta) {
                sendEvent(emitter, "token", Map.of("d", delta));
            }

            @Override
            public void onReasoning(String delta) {
                sendEvent(emitter, "reasoning", Map.of("d", delta));
            }

            @Override
            public void onToolCall(ToolCallEvent event) {
                sendEvent(emitter, "tool_call", Map.of(
                        "server", event.serverName(),
                        "tool", event.toolName(),
                        "arguments", event.arguments()));
            }

            @Override
            public void onToolResult(ToolResultEvent event) {
                sendEvent(emitter, "tool_result", Map.of(
                        "server", event.serverName(),
                        "tool", event.toolName(),
                        "ok", event.ok(),
                        "result", event.result(),
                        "latencyMs", event.latencyMs()));
            }
        };

        CompletableFuture<ChatStreamResult> future = chatService.streamChat(id, req.getContent(), listener);
        future.whenComplete((result, err) -> {
            try {
                if (err != null) {
                    // service 内部已消化所有预期异常，这里只剩不可预期的兜底
                    log.error("对话流内部错误 sessionId={}", id, err);
                    sendEvent(emitter, "error", Map.of("message", "系统内部错误"));
                } else if (result.isOk()) {
                    sendEvent(emitter, "done", Map.of(
                            "messageId", result.getMessageId() == null ? 0 : result.getMessageId(),
                            "promptTokens", result.getPromptTokens(),
                            "completionTokens", result.getCompletionTokens(),
                            "latencyMs", result.getLatencyMs()));
                } else {
                    // 失败/中断：messageId 有值说明部分内容已落库（前端气泡保留已生成部分）
                    sendEvent(emitter, "error", Map.of(
                            "message", result.getErrorMessage() == null ? "已停止" : result.getErrorMessage(),
                            "messageId", result.getMessageId() == null ? 0 : result.getMessageId()));
                }
                emitter.complete();
            } catch (Exception e) {
                // 客户端已断开（send 失败）或 complete 二次调用；不再有投递渠道，静默收尾
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // complete 本身也可能抛，吞掉
                }
            }
        });
        return emitter;
    }

    /** 发一个 SSE 事件（载荷为单行 JSON）；客户端已断开时抛 StreamAbortedException 终止上游 */
    private void sendEvent(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(json(payload), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new com.hify.chat.exception.StreamAbortedException(e);
        }
    }

    /** 序列化成单行 JSON 字符串（换行被转义，SSE 分帧安全） */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new BizException(CommonErrorCode.SYSTEM_ERROR, "SSE 事件序列化失败");
        }
    }
}
