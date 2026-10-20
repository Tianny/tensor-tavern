package com.hify.mcp.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * MCP 连通性测试结果（initialize 握手 + tools/list）。测试失败不是异常：
 * ok=false + 失败原因正常返回，与 provider 的 TestConnectionResp 同风格。
 */
@Data
public class McpTestResp {

    /** 是否连通（完成 MCP 握手并拉到工具列表） */
    private boolean ok;

    /** 结果说明（成功时含工具数量，失败时含原因） */
    private String message;

    /** 探测耗时（毫秒） */
    private Integer latencyMs;

    /** 拉取到的工具数量 */
    private Integer toolCount;

    private LocalDateTime testedAt;
}
