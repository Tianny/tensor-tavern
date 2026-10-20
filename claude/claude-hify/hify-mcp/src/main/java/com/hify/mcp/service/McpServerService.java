package com.hify.mcp.service;

import java.util.List;

import com.hify.mcp.dto.AgentToolResp;
import com.hify.mcp.dto.AgentToolUpdateReq;
import com.hify.mcp.dto.McpServerResp;
import com.hify.mcp.dto.McpServerSaveReq;
import com.hify.mcp.dto.McpTestResp;
import com.hify.mcp.entity.McpServer;

/**
 * MCP 服务管理接口（CRUD、连通性测试、Agent 工具绑定）。
 */
public interface McpServerService {

    /** 全量列表（服务数量少，不分页），id 倒序 */
    List<McpServerResp> list();

    McpServerResp getDetail(Long id);

    McpServerResp create(McpServerSaveReq req);

    /** 全量覆盖更新；headers 值为 ****** 的键保持原值 */
    McpServerResp update(Long id, McpServerSaveReq req);

    /** 逻辑删除（Agent 绑定行保留，工具解析时按服务缺失跳过） */
    void delete(Long id);

    /** 按 id 取实体（不存在抛 5001），跨模块校验用 */
    McpServer getEntity(Long id);

    /** 连通性测试：initialize 握手 + tools/list；失败不是异常，ok=false 正常返回 */
    McpTestResp testConnection(Long id);

    /** Agent 当前绑定的工具（含服务名，供表单回显） */
    List<AgentToolResp> listAgentBindings(Long agentId);

    /** 全量覆盖 Agent 的工具绑定（空 = 全部解绑） */
    void updateAgentBindings(Long agentId, AgentToolUpdateReq req);
}
