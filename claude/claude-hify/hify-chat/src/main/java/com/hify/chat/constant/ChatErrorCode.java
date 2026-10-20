package com.hify.chat.constant;

import com.hify.common.exception.ErrorCode;

import lombok.Getter;

/**
 * Chat 模块错误码（4000-4999 段）。
 */
@Getter
public enum ChatErrorCode implements ErrorCode {

    /** 会话不存在 */
    CHAT_SESSION_NOT_FOUND(4001, "会话不存在"),

    /** 消息内容为空 */
    CHAT_MESSAGE_EMPTY(4002, "消息内容不能为空"),

    /** 该提供商类型暂不支持对话流 */
    CHAT_PROVIDER_NOT_SUPPORTED(4003, "该提供商类型暂不支持对话流，当前支持：openai / openai 兼容 / claude"),

    /** Agent 绑定的模型或提供商已停用 */
    CHAT_MODEL_DISABLED(4004, "Agent 绑定的模型或提供商已停用"),

    /** Agent 已停用 */
    CHAT_AGENT_DISABLED(4005, "Agent 已停用"),

    /** 对话流失败（含中断，已生成部分会落库） */
    CHAT_STREAM_FAILED(4006, "对话失败"),

    /** 会话入口参数错误：agentId / workflowId 必须二选一 */
    CHAT_SESSION_TARGET_INVALID(4007, "会话必须关联 Agent 或工作流之一"),

    /** 会话关联的工作流不可用（不存在 / 停用） */
    CHAT_WORKFLOW_INVALID(4008, "会话关联的工作流不可用");

    private final int code;

    private final String message;

    ChatErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
