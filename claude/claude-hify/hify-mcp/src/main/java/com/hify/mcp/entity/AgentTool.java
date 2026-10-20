package com.hify.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 与 MCP 工具的绑定（M:N）。表由 mcp 模块持有，agent_id 只作列值，
 * 不反向依赖 hify-agent（绑定接口挂在前端按 agent 资源路径调用，与 agent_knowledge 同构）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_tool")
public class AgentTool extends BaseEntity {

    /** agent.id */
    private Long agentId;

    /** mcp_server.id */
    private Long mcpServerId;

    /** 绑定的具体工具名；NULL 表示绑定该服务全部工具 */
    private String toolName;
}
