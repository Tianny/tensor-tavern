package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流一次运行。运行是异步的（LLM 节点调用走 llmExecutor），
 * 前端拿 runId 轮询状态；节点级轨迹存 node_traces JSON（见 NodeTrace）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_run")
public class WorkflowRun extends BaseEntity {

    /** 执行中 */
    public static final String STATUS_RUNNING = "running";

    /** 成功 */
    public static final String STATUS_SUCCEEDED = "succeeded";

    /** 失败（节点异常 / 分支无命中 / 步数超限） */
    public static final String STATUS_FAILED = "failed";

    /** 运行的 workflow.id */
    private Long workflowId;

    /** 状态：running / succeeded / failed */
    private String status;

    /** 运行输入 JSON（start 变量名 → 值） */
    private String inputs;

    /** 最终输出（end 节点 outputTemplate 渲染结果） */
    private String output;

    /** 失败原因 */
    private String errorMsg;

    /** 节点执行轨迹 JSON 数组 */
    private String nodeTraces;

    /** 总耗时（毫秒） */
    private Integer latencyMs;
}
