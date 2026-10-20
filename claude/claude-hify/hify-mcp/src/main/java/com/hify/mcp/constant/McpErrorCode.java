package com.hify.mcp.constant;

import com.hify.common.exception.ErrorCode;

import lombok.Getter;

/**
 * MCP 模块错误码（5000-5999 段）。
 */
@Getter
public enum McpErrorCode implements ErrorCode {

    /** MCP 服务不存在 */
    MCP_SERVER_NOT_FOUND(5001, "MCP 服务不存在"),

    /** 服务名称重复 */
    MCP_SERVER_NAME_DUPLICATED(5002, "MCP 服务名称已存在"),

    /** 服务地址不合法 */
    MCP_SERVER_URL_INVALID(5003, "MCP 服务地址不合法"),

    /** 接入方式不支持（当前仅 http） */
    MCP_TRANSPORT_UNSUPPORTED(5004, "不支持的接入方式，当前仅支持 http"),

    /** 服务已停用 */
    MCP_SERVER_DISABLED(5005, "MCP 服务已停用"),

    /** 连接失败（建连/超时/HTTP 错误） */
    MCP_CONNECT_FAILED(5006, "MCP 服务连接失败"),

    /** 协议响应异常（非合法 JSON-RPC、握手失败） */
    MCP_PROTOCOL_ERROR(5007, "MCP 协议响应异常"),

    /** 工具不存在 */
    MCP_TOOL_NOT_FOUND(5008, "工具不存在"),

    /** 工具执行返回错误（result.isError=true 或参数非法） */
    MCP_TOOL_CALL_FAILED(5009, "工具调用失败");

    private final int code;

    private final String message;

    McpErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
