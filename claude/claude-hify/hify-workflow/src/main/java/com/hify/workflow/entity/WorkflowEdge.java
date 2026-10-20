package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流连线。condition 节点的出边带表达式，按 id 顺序求值取首个命中；
 * always 为兜底分支。非 condition 节点只允许一条 always 出边。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_edge")
public class WorkflowEdge extends BaseEntity {

    /** 无条件（condition 节点上作为兜底分支） */
    public static final String CONDITION_ALWAYS = "always";

    /** 表达式满足才走（仅 condition 节点出边） */
    public static final String CONDITION_EXPRESSION = "expression";

    /** 所属 workflow.id */
    private Long workflowId;

    /** 起点 node_key */
    private String sourceNodeKey;

    /** 终点 node_key */
    private String targetNodeKey;

    /** 条件类型：always / expression */
    private String conditionType;

    /** 条件表达式，如 ${judge.output} == "yes"；conditionType=always 时为空 */
    private String conditionExpr;
}
