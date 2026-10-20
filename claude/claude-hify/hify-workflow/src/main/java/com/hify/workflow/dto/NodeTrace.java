package com.hify.workflow.dto;

import lombok.Data;

/**
 * 一个节点的执行轨迹（workflow_run.node_traces JSON 数组的元素）。
 */
@Data
public class NodeTrace {

    private String nodeKey;

    private String nodeType;

    private String name;

    /** 该节点消费的输入：LLM 为渲染后的 prompt，condition 为实际命中的分支描述 */
    private String input;

    /** 节点输出：LLM 为生成文本，end 为渲染后的最终输出，condition / start 为空串 */
    private String output;

    /** 失败原因（仅失败的节点非空） */
    private String error;

    /** 该节点耗时（毫秒） */
    private Integer latencyMs;
}
