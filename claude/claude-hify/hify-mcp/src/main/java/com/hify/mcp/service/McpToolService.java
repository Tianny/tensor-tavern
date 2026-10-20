package com.hify.mcp.service;

import java.util.List;

import com.hify.mcp.dto.ToolInfoResp;
import com.hify.mcp.entity.AgentTool;

/**
 * MCP 工具调用接口（对话引擎经此拉取工具定义并执行工具调用）。
 */
public interface McpToolService {

    /** Agent 的绑定记录（agentId 维度；服务缺失/停用的处理由调用方决定） */
    List<AgentTool> listAgentTools(Long agentId);

    /**
     * 实时拉取服务的工具列表（tools/list）；服务须存在且启用。
     * 工具未提供 inputSchema 时对应字段为 null，调用方按空 schema 处理。
     */
    List<ToolInfoResp> listTools(Long serverId);

    /**
     * 执行一次工具调用（tools/call），套按服务隔离的熔断器。
     *
     * @param argumentsJson LLM 给出的参数 JSON 字符串（须为 JSON 对象）
     * @return 工具结果文本（content 里 text 项的拼接；无 text 项时为整个 result 的 JSON）
     */
    ToolCallResult callTool(Long serverId, String toolName, String argumentsJson);

    /** 工具调用结果：error=服务端标记失败（isError=true），text=结果文本 */
    record ToolCallResult(boolean error, String text) {
    }
}
