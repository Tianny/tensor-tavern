package com.hify.mcp.controller;

import java.util.List;

import com.hify.common.dto.Result;
import com.hify.mcp.dto.McpServerResp;
import com.hify.mcp.dto.McpServerSaveReq;
import com.hify.mcp.dto.McpTestResp;
import com.hify.mcp.dto.ToolInfoResp;
import com.hify.mcp.service.McpServerService;
import com.hify.mcp.service.McpToolService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * MCP 服务接口。工具列表不落库，实时经 tools/list 拉取。
 */
@RestController
@RequestMapping("/api/v1/mcp-servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;

    private final McpToolService mcpToolService;

    @GetMapping
    public Result<List<McpServerResp>> list() {
        return Result.ok(mcpServerService.list());
    }

    @PostMapping
    public Result<McpServerResp> create(@Valid @RequestBody McpServerSaveReq req) {
        return Result.ok(mcpServerService.create(req));
    }

    @GetMapping("/{id}")
    public Result<McpServerResp> detail(@PathVariable Long id) {
        return Result.ok(mcpServerService.getDetail(id));
    }

    @PutMapping("/{id}")
    public Result<McpServerResp> update(@PathVariable Long id, @Valid @RequestBody McpServerSaveReq req) {
        return Result.ok(mcpServerService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(id);
        return Result.ok();
    }

    /** 连通性测试：initialize 握手 + tools/list，失败 ok=false 正常返回 */
    @PostMapping("/{id}/test-connection")
    public Result<McpTestResp> testConnection(@PathVariable Long id) {
        return Result.ok(mcpServerService.testConnection(id));
    }

    /** 工具列表（实时拉取，不缓存不落库） */
    @GetMapping("/{id}/tools")
    public Result<List<ToolInfoResp>> listTools(@PathVariable Long id) {
        return Result.ok(mcpToolService.listTools(id));
    }
}
