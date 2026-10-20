package com.hify.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 工作流连线保存请求。condition 节点出边须带表达式（或 always 兜底），
 * 非 condition 节点只允许 always。
 */
@Data
public class WorkflowEdgeReq {

    @NotBlank(message = "起点 nodeKey 不能为空")
    private String sourceNodeKey;

    @NotBlank(message = "终点 nodeKey 不能为空")
    private String targetNodeKey;

    @NotBlank(message = "条件类型不能为空")
    @Pattern(regexp = "^(always|expression)$", message = "条件类型仅支持 always/expression")
    private String conditionType;

    /** 条件表达式，conditionType=expression 时必填，语法见 ConditionEvaluator */
    @Size(max = 512, message = "条件表达式最长 512 字")
    private String conditionExpr;
}
