package com.hify.chat.dto;

import java.time.LocalDateTime;

import com.hify.chat.entity.ChatSession;

import lombok.Data;

/**
 * 会话响应。
 */
@Data
public class ChatSessionResp {

    private Long id;

    private Long agentId;

    /** 工作流会话关联的 workflow.id，Agent 会话为 null */
    private Long workflowId;

    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static ChatSessionResp of(ChatSession session) {
        ChatSessionResp resp = new ChatSessionResp();
        resp.setId(session.getId());
        resp.setAgentId(session.getAgentId());
        resp.setWorkflowId(session.getWorkflowId());
        resp.setTitle(session.getTitle());
        resp.setCreatedAt(session.getCreatedAt());
        resp.setUpdatedAt(session.getUpdatedAt());
        return resp;
    }
}
