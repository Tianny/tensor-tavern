package com.hify.workflow.constant;

import com.hify.common.exception.ErrorCode;

import lombok.Getter;

/**
 * Workflow 模块错误码（6000-6999 段）。
 */
@Getter
public enum WorkflowErrorCode implements ErrorCode {

    /** 工作流不存在 */
    WORKFLOW_NOT_FOUND(6001, "工作流不存在"),

    /** 工作流名称重复 */
    WORKFLOW_NAME_DUPLICATED(6002, "工作流名称已存在"),

    /** 节点配置不合法（nodeKey 格式 / 类型 / config 缺字段等） */
    WORKFLOW_NODE_INVALID(6003, "节点配置不合法"),

    /** 图结构不合法（缺 start/end、悬空连线、不可达节点、非条件节点多出边等） */
    WORKFLOW_GRAPH_INVALID(6004, "工作流图结构不合法"),

    /** 工作流已停用 */
    WORKFLOW_DISABLED(6005, "工作流已停用"),

    /** 运行记录不存在 */
    WORKFLOW_RUN_NOT_FOUND(6006, "运行记录不存在"),

    /** LLM 节点绑定的模型不可用（不存在 / 停用 / 类型不对 / 提供商不支持） */
    WORKFLOW_LLM_MODEL_INVALID(6007, "LLM 节点绑定的模型不可用"),

    /** 条件表达式语法不合法 */
    WORKFLOW_EXPR_INVALID(6008, "条件表达式不合法"),

    /** 起点变量缺失（必填的 start 变量未传） */
    WORKFLOW_RUN_INPUT_INVALID(6009, "运行输入缺少必填的起点变量"),

    /** 执行步数超过上限（疑似条件分支成环死循环） */
    WORKFLOW_STEP_LIMIT(6010, "执行步数超过上限，疑似存在死循环"),

    /** 工作流不满足对话入口契约（start 无变量 / 除首变量外还有必填变量） */
    WORKFLOW_CHAT_ENTRY_MISSING(6011, "工作流缺少对话入口变量（start 节点需声明变量且仅第一个可为必填）");

    private final int code;

    private final String message;

    WorkflowErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
