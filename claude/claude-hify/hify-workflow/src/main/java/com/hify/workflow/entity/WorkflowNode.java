package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流节点。config 为 JSON 字符串，结构按 nodeType 约定：
 *
 * <pre>
 * start     {"variables":[{"name":"query","required":true}]}
 * llm       {"modelConfigId":123,"prompt":"…${start.query}…","temperature":0.7,"maxTokens":0}
 * condition {}（路由条件配置在出边上）
 * end       {"outputTemplate":"${llm1.output}"}
 * </pre>
 *
 * 变量池引用键：start 输入为 {@code start节点Key.变量名}，LLM 节点输出为 {@code 节点Key.output}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_node")
public class WorkflowNode extends BaseEntity {

    /** 节点类型 */
    public static final String TYPE_START = "start";

    /** 节点类型 */
    public static final String TYPE_LLM = "llm";

    /** 节点类型 */
    public static final String TYPE_CONDITION = "condition";

    /** 节点类型 */
    public static final String TYPE_END = "end";

    /** 所属 workflow.id */
    private Long workflowId;

    /** 节点标识，工作流内唯一，连线按此引用 */
    private String nodeKey;

    /** 节点类型：start / llm / condition / end（tool 等 MCP 落地后补） */
    private String nodeType;

    /** 节点名称（展示用） */
    private String name;

    /** 节点配置 JSON（结构见类注释） */
    private String config;
}
