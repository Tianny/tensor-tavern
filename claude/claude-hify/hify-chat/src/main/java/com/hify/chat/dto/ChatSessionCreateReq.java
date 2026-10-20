package com.hify.chat.dto;

import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建会话请求。agentId / workflowId 二选一（都传或都不传由 Service 校验）。
 */
@Data
public class ChatSessionCreateReq {

    /** 对话的 Agent（与 workflowId 二选一） */
    private Long agentId;

    /** 直连的工作流（与 agentId 二选一），消息走工作流执行 */
    private Long workflowId;

    /** 标题，可空（首条消息自动生成） */
    @Size(max = 128)
    private String title;
}
