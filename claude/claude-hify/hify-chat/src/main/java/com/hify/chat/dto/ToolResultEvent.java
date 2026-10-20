package com.hify.chat.dto;

/**
 * 工具执行结果事件（SSE tool_result 事件载荷）。result 为展示用摘要（截断），
 * 完整结果只回传给 LLM。
 */
public record ToolResultEvent(String serverName, String toolName, boolean ok, String result, int latencyMs) {
}
