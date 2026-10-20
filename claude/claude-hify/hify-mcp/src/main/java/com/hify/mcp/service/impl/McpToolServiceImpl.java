package com.hify.mcp.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.exception.BizException;
import com.hify.common.metrics.HifyMetrics;
import com.hify.common.resilience.CircuitBreakerService;
import com.hify.mcp.constant.McpErrorCode;
import com.hify.mcp.dto.ToolInfoResp;
import com.hify.mcp.entity.AgentTool;
import com.hify.mcp.entity.McpServer;
import com.hify.mcp.mapper.AgentToolMapper;
import com.hify.mcp.mapper.McpServerMapper;
import com.hify.mcp.service.McpToolService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP 工具调用实现：listTools / callTool 均走 {@link McpHttpClient}（JSON-RPC），
 * 外面套按服务隔离的熔断器（mcp:{serverId}）。工具调用不做重试
 * （外部工具未必幂等，失败直接抛，由对话层决定报错不降级）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolServiceImpl implements McpToolService {

    private static final String BREAKER_PREFIX = "mcp:";

    private final McpServerMapper serverMapper;

    private final AgentToolMapper agentToolMapper;

    private final McpHttpClient httpClient;

    private final CircuitBreakerService circuitBreakerService;

    private final ObjectMapper objectMapper;

    private final HifyMetrics hifyMetrics;

    @Override
    public List<AgentTool> listAgentTools(Long agentId) {
        return agentToolMapper.selectList(new LambdaQueryWrapper<AgentTool>()
                .eq(AgentTool::getAgentId, agentId));
    }

    @Override
    public List<ToolInfoResp> listTools(Long serverId) {
        McpServer server = requireEnabled(serverId);
        JsonNode result = circuitBreakerService.execute(
                BREAKER_PREFIX + serverId,
                () -> httpClient.request(server, "tools/list", null));
        List<ToolInfoResp> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            if (!tool.path("name").isTextual()) {
                continue;   // 无名工具跳过（无法调用）
            }
            ToolInfoResp info = new ToolInfoResp();
            info.setName(tool.path("name").asText());
            info.setDescription(tool.path("description").asText(""));
            info.setInputSchema(tool.path("inputSchema").isObject() ? tool.path("inputSchema") : null);
            tools.add(info);
        }
        return tools;
    }

    @Override
    public ToolCallResult callTool(Long serverId, String toolName, String argumentsJson) {
        McpServer server = requireEnabled(serverId);
        try {
            ToolCallResult result = doCallTool(server, toolName, argumentsJson);
            hifyMetrics.recordMcpToolCall(server.getName(), toolName, true);
            return result;
        } catch (Exception e) {
            hifyMetrics.recordMcpToolCall(server.getName(), toolName, false);
            throw e;
        }
    }

    private ToolCallResult doCallTool(McpServer server, String toolName, String argumentsJson) {
        // 参数由 LLM 生成，先校验是合法 JSON 对象再发（不合法直接报工具调用失败，不重试）
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson);
        } catch (Exception e) {
            throw new BizException(McpErrorCode.MCP_TOOL_CALL_FAILED,
                    "工具 " + toolName + " 的参数不是合法 JSON：" + argumentsJson);
        }
        if (!arguments.isObject()) {
            throw new BizException(McpErrorCode.MCP_TOOL_CALL_FAILED,
                    "工具 " + toolName + " 的参数必须是 JSON 对象");
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);

        log.info("MCP 工具调用开始 server={} tool={} 参数={}", server.getName(), toolName, argumentsJson);
        long start = System.currentTimeMillis();
        JsonNode result = circuitBreakerService.execute(
                BREAKER_PREFIX + server.getId(),
                () -> httpClient.request(server, "tools/call", params));

        boolean isError = result.path("isError").asBoolean(false);
        String text = extractText(result, toolName);
        log.info("MCP 工具调用结束 server={} tool={} ok={} 耗时={}ms",
                server.getName(), toolName, !isError, System.currentTimeMillis() - start);
        if (isError) {
            throw new BizException(McpErrorCode.MCP_TOOL_CALL_FAILED,
                    "工具 " + toolName + " 返回错误：" + text);
        }
        return new ToolCallResult(false, text);
    }

    // ---------- 内部 ----------

    /** 服务存在且启用；JSON-RPC 层的协议错误在工具调用语境下归因为工具调用失败 */
    private McpServer requireEnabled(Long serverId) {
        McpServer server = serverMapper.selectById(serverId);
        if (server == null) {
            throw new BizException(McpErrorCode.MCP_SERVER_NOT_FOUND, "MCP 服务不存在，id=" + serverId);
        }
        if (server.getEnabled() == null || server.getEnabled() != 1) {
            throw new BizException(McpErrorCode.MCP_SERVER_DISABLED, "MCP 服务已停用：" + server.getName());
        }
        return server;
    }

    /** content 数组里 text 项的拼接；没有 text 项时回退为整个 result 的 JSON */
    private String extractText(JsonNode result, String toolName) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : result.path("content")) {
            if ("text".equals(item.path("type").asText())) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(item.path("text").asText(""));
            }
        }
        if (!text.isEmpty()) {
            return text.toString();
        }
        return result.toString();
    }
}
