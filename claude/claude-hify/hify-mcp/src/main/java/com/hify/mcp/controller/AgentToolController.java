package com.hify.mcp.controller;

import java.util.List;

import com.hify.common.dto.Result;
import com.hify.mcp.dto.AgentToolResp;
import com.hify.mcp.dto.AgentToolUpdateReq;
import com.hify.mcp.service.McpServerService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Agent 与 MCP 工具的绑定接口。挂在 agent 资源路径下（绑定表由本模块持有，
 * 不反向依赖 hify-agent——agent_id 只是列值，与 agent_knowledge 同构）。
 */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/tools")
@RequiredArgsConstructor
public class AgentToolController {

    private final McpServerService mcpServerService;

    /** Agent 当前绑定的工具（含服务名，供表单回显） */
    @GetMapping
    public Result<List<AgentToolResp>> listBound(@PathVariable Long agentId) {
        return Result.ok(mcpServerService.listAgentBindings(agentId));
    }

    /** 全量覆盖绑定（空数组 = 全部解绑） */
    @PutMapping
    public Result<Void> updateBindings(@PathVariable Long agentId, @Valid @RequestBody AgentToolUpdateReq req) {
        mcpServerService.updateAgentBindings(agentId, req);
        return Result.ok();
    }
}
