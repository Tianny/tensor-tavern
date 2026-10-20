package com.hify.mcp.dto;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.Data;

/**
 * MCP 工具信息（tools/list 实时拉取，不落库）。
 */
@Data
public class ToolInfoResp {

    /** 工具名（调用时传给 tools/call 的 name） */
    private String name;

    /** 工具描述（会作为 function calling 的工具说明发给 LLM） */
    private String description;

    /** 输入参数 JSON Schema */
    private JsonNode inputSchema;
}
