package com.hify.mcp.dto;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建/更新 MCP 服务（全量覆盖语义）。接入方式当前仅 http，
 * command/args（stdio 预留）不在接口上暴露。
 */
@Data
public class McpServerSaveReq {

    @NotBlank
    @Size(max = 64)
    private String name;

    @Size(max = 255)
    private String description;

    /** 接入方式，只允许 http（stdio 未实现，传其他值报 5004） */
    @Size(max = 16)
    private String transport;

    /** MCP 端点地址，须为 http(s):// 开头 */
    @NotBlank
    @Size(max = 255)
    private String url;

    /** 附加请求头 JSON 对象（鉴权等）；值传 "******" 表示保持原值（掩码回显兼容） */
    private JsonNode headers;

    /** 是否启用，可空默认 1 */
    @Min(0)
    @Max(1)
    private Integer enabled;
}
