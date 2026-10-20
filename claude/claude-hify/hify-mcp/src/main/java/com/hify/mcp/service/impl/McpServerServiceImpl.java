package com.hify.mcp.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.exception.BizException;
import com.hify.mcp.constant.McpErrorCode;
import com.hify.mcp.dto.AgentToolResp;
import com.hify.mcp.dto.AgentToolUpdateReq;
import com.hify.mcp.dto.McpServerResp;
import com.hify.mcp.dto.McpServerSaveReq;
import com.hify.mcp.dto.McpTestResp;
import com.hify.mcp.dto.ToolInfoResp;
import com.hify.mcp.entity.AgentTool;
import com.hify.mcp.entity.McpServer;
import com.hify.mcp.mapper.AgentToolMapper;
import com.hify.mcp.mapper.McpServerMapper;
import com.hify.mcp.service.McpServerService;
import com.hify.mcp.service.McpToolService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP 服务管理实现。保存校验：名称唯一、transport 仅 http、url 须 http(s)://、
 * headers 须为 JSON 对象。URL/headers 变更后失效传输层的会话缓存
 * （会话与旧地址/旧鉴权绑定）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl implements McpServerService {

    private static final String MASK = "******";

    private final McpServerMapper serverMapper;

    private final AgentToolMapper agentToolMapper;

    private final McpToolService mcpToolService;

    private final McpHttpClient httpClient;

    private final ObjectMapper objectMapper;

    @Override
    public List<McpServerResp> list() {
        return serverMapper.selectList(new LambdaQueryWrapper<McpServer>()
                        .orderByDesc(McpServer::getId))
                .stream()
                .map(server -> McpServerResp.of(server, objectMapper))
                .toList();
    }

    @Override
    public McpServerResp getDetail(Long id) {
        return McpServerResp.of(getEntity(id), objectMapper);
    }

    @Override
    public McpServerResp create(McpServerSaveReq req) {
        validate(req, null);
        McpServer server = new McpServer();
        applyFields(server, req, null);
        serverMapper.insert(server);
        return getDetail(server.getId());
    }

    @Override
    public McpServerResp update(Long id, McpServerSaveReq req) {
        McpServer exists = getEntity(id);
        validate(req, id);
        McpServer server = new McpServer();
        server.setId(id);
        applyFields(server, req, exists);
        serverMapper.updateById(server);
        // url/headers 变了，旧会话作废
        boolean endpointChanged = !Objects.equals(exists.getUrl(), req.getUrl());
        boolean headersChanged = req.getHeaders() != null;
        if (endpointChanged || headersChanged) {
            httpClient.invalidateSession(id);
        }
        return getDetail(id);
    }

    @Override
    public void delete(Long id) {
        getEntity(id);
        serverMapper.deleteById(id);   // 逻辑删除
        httpClient.invalidateSession(id);
    }

    @Override
    public McpServer getEntity(Long id) {
        McpServer server = serverMapper.selectById(id);
        if (server == null) {
            throw new BizException(McpErrorCode.MCP_SERVER_NOT_FOUND, "MCP 服务不存在，id=" + id);
        }
        return server;
    }

    @Override
    public McpTestResp testConnection(Long id) {
        McpServer server = getEntity(id);
        McpTestResp resp = new McpTestResp();
        resp.setTestedAt(LocalDateTime.now());
        long start = System.currentTimeMillis();
        try {
            List<ToolInfoResp> tools = mcpToolService.listTools(id);
            resp.setOk(true);
            resp.setToolCount(tools.size());
            resp.setMessage("连接成功，握手完成，共 " + tools.size() + " 个工具");
        } catch (Exception e) {
            resp.setOk(false);
            resp.setMessage(e.getMessage() == null ? "连接失败" : e.getMessage());
            log.info("MCP 连通性测试失败 serverId={} url={} 原因={}", id, server.getUrl(), e.getMessage());
        }
        resp.setLatencyMs((int) (System.currentTimeMillis() - start));
        return resp;
    }

    @Override
    public List<AgentToolResp> listAgentBindings(Long agentId) {
        List<AgentTool> bindings = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getAgentId, agentId));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, McpServer> servers = bindings.stream()
                .map(AgentTool::getMcpServerId)
                .distinct()
                .map(serverMapper::selectById)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(McpServer::getId, Function.identity()));
        return bindings.stream()
                .map(binding -> AgentToolResp.of(binding, servers.get(binding.getMcpServerId())))
                .toList();
    }

    @Override
    @Transactional
    public void updateAgentBindings(Long agentId, AgentToolUpdateReq req) {
        // 先校验全部绑定，再全量覆盖（避免删了旧的、新的校验不过导致中间态）
        for (AgentToolUpdateReq.Binding binding : req.getBindings()) {
            McpServer server = getEntity(binding.getMcpServerId());
            if (server.getEnabled() == null || server.getEnabled() != 1) {
                throw new BizException(McpErrorCode.MCP_SERVER_DISABLED,
                        "MCP 服务已停用，不能绑定：" + server.getName());
            }
        }
        agentToolMapper.delete(new LambdaQueryWrapper<AgentTool>()
                .eq(AgentTool::getAgentId, agentId));
        for (AgentToolUpdateReq.Binding binding : req.getBindings()) {
            AgentTool tool = new AgentTool();
            tool.setAgentId(agentId);
            tool.setMcpServerId(binding.getMcpServerId());
            tool.setToolName(binding.getToolName() == null || binding.getToolName().isBlank()
                    ? null : binding.getToolName().trim());
            agentToolMapper.insert(tool);
        }
    }

    // ---------- 内部 ----------

    private void validate(McpServerSaveReq req, Long excludeId) {
        checkNameDuplicate(req.getName().trim(), excludeId);
        String transport = req.getTransport() == null || req.getTransport().isBlank()
                ? McpServer.TRANSPORT_HTTP : req.getTransport().trim();
        if (!McpServer.TRANSPORT_HTTP.equals(transport)) {
            throw new BizException(McpErrorCode.MCP_TRANSPORT_UNSUPPORTED,
                    "接入方式仅支持 http，当前：" + transport);
        }
        String url = req.getUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BizException(McpErrorCode.MCP_SERVER_URL_INVALID,
                    "地址必须以 http:// 或 https:// 开头：" + url);
        }
        if (req.getHeaders() != null && !req.getHeaders().isNull() && !req.getHeaders().isObject()) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR, "headers 必须是 JSON 对象");
        }
    }

    private void checkNameDuplicate(String name, Long excludeId) {
        LambdaQueryWrapper<McpServer> wrapper = new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getName, name);
        if (excludeId != null) {
            wrapper.ne(McpServer::getId, excludeId);
        }
        if (serverMapper.selectCount(wrapper) > 0) {
            throw new BizException(McpErrorCode.MCP_SERVER_NAME_DUPLICATED, "名称已存在：" + name);
        }
    }

    /** req → 实体；headers 值为 ****** 的键用旧值补齐（掩码回显不覆盖真实值） */
    private void applyFields(McpServer server, McpServerSaveReq req, McpServer exists) {
        server.setName(req.getName().trim());
        server.setDescription(req.getDescription() == null ? "" : req.getDescription().trim());
        server.setTransport(McpServer.TRANSPORT_HTTP);
        server.setUrl(req.getUrl().trim());
        server.setHeaders(mergeHeaders(req.getHeaders(), exists));
        server.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
    }

    /** null=清空；对象=逐键覆盖，值为掩码的键取旧值 */
    private String mergeHeaders(JsonNode newHeaders, McpServer exists) {
        if (newHeaders == null || newHeaders.isNull()) {
            return null;
        }
        JsonNode parsed = null;
        if (exists != null && exists.getHeaders() != null && !exists.getHeaders().isBlank()) {
            try {
                parsed = objectMapper.readTree(exists.getHeaders());
            } catch (Exception ignored) {
                // 旧值非法 JSON：按无旧值处理，直接全量覆盖
            }
        }
        final JsonNode oldHeaders = parsed;
        ObjectNode merged = newHeaders.deepCopy();
        merged.fields().forEachRemaining(entry -> {
            if (MASK.equals(entry.getValue().asText(null)) && oldHeaders != null) {
                JsonNode old = oldHeaders.path(entry.getKey());
                if (!old.isMissingNode()) {
                    merged.set(entry.getKey(), old);
                }
            }
        });
        return merged.toString();
    }
}
