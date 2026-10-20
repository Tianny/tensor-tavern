package com.hify.mcp.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 更新 Agent 绑定的工具集合（全量覆盖，与 agent_knowledge 同构）。
 */
@Data
public class AgentToolUpdateReq {

    /** 绑定列表，空 = 全部解绑 */
    @NotNull(message = "bindings 不能为 null（空数组表示全部解绑）")
    private List<Binding> bindings;

    /** 单条绑定 */
    @Data
    public static class Binding {

        /** mcp_server.id（须存在且启用） */
        @NotNull
        private Long mcpServerId;

        /** 绑定的具体工具名；null/空 = 该服务全部工具 */
        @Size(max = 128)
        private String toolName;
    }
}
