package com.hify.agent.constant;

import com.hify.common.exception.ErrorCode;

import lombok.Getter;

/**
 * Agent 模块错误码（3000-3999 段）。
 */
@Getter
public enum AgentErrorCode implements ErrorCode {

    /** Agent 不存在 */
    AGENT_NOT_FOUND(3001, "Agent 不存在"),

    /** 名称重复 */
    AGENT_NAME_DUPLICATED(3002, "Agent 名称已存在"),

    /** 绑定的模型配置不可用（不存在、已删除、停用或非 chat 类型） */
    AGENT_MODEL_CONFIG_INVALID(3003, "绑定的模型配置不可用");

    private final int code;

    private final String message;

    AgentErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
