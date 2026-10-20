package com.hify.mcp.dto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.mcp.entity.McpServer;

import lombok.Data;

/**
 * MCP 服务响应。headers 的值做掩码回显（可能携带鉴权 token，与 provider.api_key 同策略）。
 */
@Data
public class McpServerResp {

    private Long id;

    private String name;

    private String description;

    private String transport;

    private String url;

    /** 请求头（值已掩码为 ******），空时返回空对象 */
    private Map<String, String> headers;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static McpServerResp of(McpServer entity, ObjectMapper objectMapper) {
        McpServerResp resp = new McpServerResp();
        resp.setId(entity.getId());
        resp.setName(entity.getName());
        resp.setDescription(entity.getDescription());
        resp.setTransport(entity.getTransport());
        resp.setUrl(entity.getUrl());
        resp.setHeaders(maskHeaders(entity.getHeaders(), objectMapper));
        resp.setEnabled(entity.getEnabled());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }

    /** 值统一掩码；headers 非法 JSON 时返回空对象（不因脏数据炸列表） */
    private static Map<String, String> maskHeaders(String headersJson, ObjectMapper objectMapper) {
        Map<String, String> masked = new LinkedHashMap<>();
        if (headersJson == null || headersJson.isBlank()) {
            return masked;
        }
        try {
            JsonNode node = objectMapper.readTree(headersJson);
            node.fields().forEachRemaining(entry ->
                    masked.put(entry.getKey(), entry.getValue().isTextual() ? "******" : entry.getValue().toString()));
        } catch (Exception ignored) {
            // 非法 JSON：留空，更新时会按全量覆盖写回
        }
        return masked;
    }
}
