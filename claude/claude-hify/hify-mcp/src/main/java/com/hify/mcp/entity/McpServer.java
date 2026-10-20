package com.hify.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MCP 工具服务（外部工具的接入点）。当前仅支持 http（Streamable HTTP）接入；
 * command/args 为 stdio 预留字段，stdio 接入暂未实现。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mcp_server")
public class McpServer extends BaseEntity {

    public static final String TRANSPORT_HTTP = "http";

    /** 服务名称 */
    private String name;

    /** 服务描述 */
    private String description;

    /** 接入方式：http */
    private String transport;

    /** HTTP 接入时的服务地址（MCP 端点，POST JSON-RPC） */
    private String url;

    /** stdio 预留：启动命令 */
    private String command;

    /** stdio 预留：启动参数 JSON 数组 */
    private String args;

    /** HTTP 请求头 JSON 对象（鉴权等），如 {"Authorization":"Bearer xxx"} */
    private String headers;

    /** 是否启用：0=停用 1=启用 */
    private Integer enabled;
}
