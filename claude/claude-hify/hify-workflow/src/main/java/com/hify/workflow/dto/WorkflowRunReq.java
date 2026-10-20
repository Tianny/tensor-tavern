package com.hify.workflow.dto;

import java.util.Map;

import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 运行工作流请求。inputs 为 start 节点声明的变量名 → 值，
 * 必填变量缺失由 Service 同步校验（6012 快速反馈，不等异步失败）。
 */
@Data
public class WorkflowRunReq {

    /** start 变量值；未声明的变量名忽略，必填缺失报 6009 */
    @Size(max = 16, message = "输入变量最多 16 个")
    private Map<String, String> inputs;
}
