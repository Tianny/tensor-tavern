package com.hify.chat.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.hify.chat.dto.ChatMessageResp;
import com.hify.chat.dto.ChatSessionCreateReq;
import com.hify.chat.dto.ChatSessionResp;
import com.hify.chat.dto.ChatStreamResult;

/**
 * 对话服务接口。
 */
public interface ChatService {

    /**
     * 创建会话。agentId / workflowId 二选一：Agent 会话走模型直答，
     * 工作流会话每条消息触发一次工作流执行。
     */
    ChatSessionResp createSession(ChatSessionCreateReq req);

    /** 会话列表（按 agent 或 workflow 过滤，二选一），按最后活跃倒序 */
    List<ChatSessionResp> listSessions(Long agentId, Long workflowId);

    /** 逻辑删除会话（同时清 Redis 上下文） */
    void deleteSession(Long id);

    /**
     * 消息游标分页（不做 COUNT）：before 为空取最新一页，
     * 否则取 id < before 的更早消息；返回按时间正序。
     */
    List<ChatMessageResp> listMessages(Long sessionId, Long before, Integer limit);

    /**
     * 发送一条用户消息并流式生成回复。
     *
     * <p>同步段（调用线程）：校验会话/Agent/模型可用、落库用户消息、加载上下文；
     * 校验失败抛 BizException（此时 HTTP 响应还未切换成 SSE，走全局异常处理回 Result JSON）。
     *
     * <p>异步段（llmExecutor）：熔断 + 重试 + 流式调用，每个增量经 listener 推给客户端。
     *
     * @param listener 增量回调；onToken 抛异常（客户端断开）会中止流并保留已生成内容
     * @return future 正常结束携带 {@link ChatStreamResult}（含失败情形，ok=false）；
     *         只有不可预期的内部错误才会异常结束
     */
    CompletableFuture<ChatStreamResult> streamChat(Long sessionId, String content,
            ChatStreamListener listener);
}
