package com.hify.chat.dto;

/**
 * 工具调用事件（SSE tool_call 事件载荷）。模型决定调用某个 MCP 工具时推送，
 * 前端渲染为工具卡片。只做流式展示，不落库（与思考流同策略）。
 */
public record ToolCallEvent(String serverName, String toolName, String arguments) {
}
