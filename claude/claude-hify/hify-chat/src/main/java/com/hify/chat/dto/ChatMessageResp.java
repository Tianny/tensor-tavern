package com.hify.chat.dto;

import java.time.LocalDateTime;

import com.hify.chat.entity.ChatMessage;

import lombok.Data;

/**
 * 消息响应（游标分页：按 id 倒序取、时间正序返回）。
 */
@Data
public class ChatMessageResp {

    private Long id;

    private Long sessionId;

    private String role;

    private String content;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer latencyMs;

    private LocalDateTime createdAt;

    public static ChatMessageResp of(ChatMessage message) {
        ChatMessageResp resp = new ChatMessageResp();
        resp.setId(message.getId());
        resp.setSessionId(message.getSessionId());
        resp.setRole(message.getRole());
        resp.setContent(message.getContent());
        resp.setPromptTokens(message.getPromptTokens());
        resp.setCompletionTokens(message.getCompletionTokens());
        resp.setLatencyMs(message.getLatencyMs());
        resp.setCreatedAt(message.getCreatedAt());
        return resp;
    }
}
