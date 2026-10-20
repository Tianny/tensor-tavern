package com.hify.workflow.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.hify.workflow.entity.WorkflowRun;

import lombok.Data;

/**
 * 工作流运行记录响应（含节点轨迹）。轮询 / 历史查询共用。
 */
@Data
public class WorkflowRunResp {

    private Long id;

    private Long workflowId;

    private String status;

    private Map<String, String> inputs;

    private String output;

    private String errorMsg;

    private List<NodeTrace> traces;

    private Integer latencyMs;

    private LocalDateTime createdAt;

    /** 摘要版（历史列表用，不带轨迹） */
    @Data
    public static class Summary {

        private Long id;

        private Long workflowId;

        private String status;

        private String output;

        private String errorMsg;

        private Integer latencyMs;

        private LocalDateTime createdAt;

        public static Summary of(WorkflowRun run) {
            Summary s = new Summary();
            s.setId(run.getId());
            s.setWorkflowId(run.getWorkflowId());
            s.setStatus(run.getStatus());
            s.setOutput(run.getOutput() == null ? "" : run.getOutput());
            s.setErrorMsg(run.getErrorMsg() == null ? "" : run.getErrorMsg());
            s.setLatencyMs(run.getLatencyMs());
            s.setCreatedAt(run.getCreatedAt());
            return s;
        }
    }

    public static WorkflowRunResp of(WorkflowRun run, Map<String, String> inputs, List<NodeTrace> traces) {
        WorkflowRunResp resp = new WorkflowRunResp();
        resp.setId(run.getId());
        resp.setWorkflowId(run.getWorkflowId());
        resp.setStatus(run.getStatus());
        resp.setInputs(inputs);
        resp.setOutput(run.getOutput() == null ? "" : run.getOutput());
        resp.setErrorMsg(run.getErrorMsg() == null ? "" : run.getErrorMsg());
        resp.setTraces(traces);
        resp.setLatencyMs(run.getLatencyMs() == null ? 0 : run.getLatencyMs());
        resp.setCreatedAt(run.getCreatedAt());
        return resp;
    }
}
