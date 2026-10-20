package com.hify.provider.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 连通性测试结果。测试失败不是异常：ok=false + 失败原因正常返回。
 */
@Data
public class TestConnectionResp {

    /** 是否连通 */
    private boolean ok;

    /** 结果说明（成功时含模型数量，失败时含原因） */
    private String message;

    /** 探测耗时（毫秒，含重试外的单次请求） */
    private Integer latencyMs;

    /** 探测到的模型数量；null=响应里没有模型列表 */
    private Integer modelCount;

    private LocalDateTime testedAt;
}
