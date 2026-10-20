package com.hify.chat.dto;

import lombok.Data;

/**
 * 一次流式对话的结果。ok=false 时 content 为已生成的部分内容（可能为空串），
 * message 持久化与否取决于 content 是否非空。
 */
@Data
public class ChatStreamResult {

    private boolean ok;

    /** 失败原因（ok=false 时），客户端断开为 null */
    private String errorMessage;

    /** 落库后的 assistant 消息 id；未落库为 null */
    private Long messageId;

    /** 完整回复内容（含中断时的部分内容） */
    private String content;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer latencyMs;
}
