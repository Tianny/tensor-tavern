package com.hify.mcp.dto;

import com.hify.mcp.entity.AgentTool;
import com.hify.mcp.entity.McpServer;

import lombok.Data;

/**
 * Agent 工具绑定响应（含所属服务名，供表单回显）。
 */
@Data
public class AgentToolResp {

    private Long mcpServerId;

    /** 服务名（服务已删时为空串） */
    private String serverName;

    /** 绑定的工具名；null=该服务全部工具 */
    private String toolName;

    public static AgentToolResp of(AgentTool binding, McpServer server) {
        AgentToolResp resp = new AgentToolResp();
        resp.setMcpServerId(binding.getMcpServerId());
        resp.setServerName(server == null ? "" : server.getName());
        resp.setToolName(binding.getToolName());
        return resp;
    }
}
