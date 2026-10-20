package com.hify.workflow.service.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.workflow.constant.WorkflowErrorCode;
import com.hify.workflow.dto.NodeTrace;
import com.hify.workflow.dto.WorkflowRunReq;
import com.hify.workflow.dto.WorkflowRunResp;
import com.hify.workflow.entity.Workflow;
import com.hify.workflow.entity.WorkflowNode;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.mapper.WorkflowNodeMapper;
import com.hify.workflow.mapper.WorkflowRunMapper;
import com.hify.workflow.service.WorkflowRunService;
import com.hify.workflow.service.WorkflowService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作流运行入口：请求线程只做校验 + 建运行记录，引擎提交 llmExecutor
 * （引擎内的 LLM 节点调用即在该线程池上执行，符合线程池使用规则）。
 * 运行历史查询同在此处。
 */
@Slf4j
@Service
public class WorkflowRunServiceImpl implements WorkflowRunService {

    /** 最近运行记录条数上限 */
    private static final int MAX_RECENT = 50;

    private final WorkflowRunMapper runMapper;

    private final WorkflowNodeMapper nodeMapper;

    private final WorkflowService workflowService;

    private final WorkflowEngine engine;

    private final ObjectMapper objectMapper;

    private final ThreadPoolTaskExecutor llmExecutor;

    public WorkflowRunServiceImpl(
            WorkflowRunMapper runMapper,
            WorkflowNodeMapper nodeMapper,
            WorkflowService workflowService,
            WorkflowEngine engine,
            ObjectMapper objectMapper,
            @Qualifier("llmExecutor") ThreadPoolTaskExecutor llmExecutor) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        this.workflowService = workflowService;
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.llmExecutor = llmExecutor;
    }

    @Override
    public Long run(Long workflowId, WorkflowRunReq req) {
        Workflow workflow = workflowService.getEntity(workflowId);
        if (workflow.getEnabled() != 1) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_DISABLED, "工作流已停用：" + workflow.getName());
        }
        // start 节点必填变量同步校验（快速反馈，不等到异步失败）
        Map<String, String> inputs = req.getInputs() == null ? Map.of() : req.getInputs();
        validateRequiredInputs(workflowId, inputs);

        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(workflowId);
        run.setStatus(WorkflowRun.STATUS_RUNNING);
        run.setInputs(writeJson(inputs));
        run.setOutput("");
        run.setErrorMsg("");
        run.setNodeTraces("[]");
        run.setLatencyMs(0);
        runMapper.insert(run);

        Long runId = run.getId();
        llmExecutor.execute(() -> {
            try {
                engine.execute(runId);
            } catch (Exception e) {
                // 引擎承诺不抛；这里兜底防线程池吞异常后运行记录永远 running
                log.error("工作流引擎执行异常 runId={}", runId, e);
                markFailed(runId, "工作流执行异常：" + e.getMessage());
            }
        });
        log.info("工作流已提交运行 runId={} workflowId={} name={}", runId, workflowId, workflow.getName());
        return runId;
    }

    @Override
    public WorkflowRunResp getRun(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_RUN_NOT_FOUND, "运行记录不存在，id=" + runId);
        }
        return WorkflowRunResp.of(run, parseInputs(run), parseTraces(run));
    }

    @Override
    public List<WorkflowRunResp.Summary> listRuns(Long workflowId, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_RECENT);
        return runMapper.selectRecentByWorkflow(workflowId, size).stream()
                .map(WorkflowRunResp.Summary::of)
                .toList();
    }

    @Override
    public com.hify.workflow.entity.WorkflowRun runInline(Long workflowId, Map<String, String> inputs,
            java.util.function.Consumer<String> progress) {
        Workflow workflow = workflowService.getEntity(workflowId);
        if (workflow.getEnabled() != 1) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_DISABLED, "工作流已停用：" + workflow.getName());
        }
        validateRequiredInputs(workflowId, inputs);

        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(workflowId);
        run.setStatus(WorkflowRun.STATUS_RUNNING);
        run.setInputs(writeJson(inputs));
        run.setOutput("");
        run.setErrorMsg("");
        run.setNodeTraces("[]");
        run.setLatencyMs(0);
        runMapper.insert(run);
        // 引擎在当前线程执行（调用方已保证在 llmExecutor 上），对话链路同步等结果
        engine.execute(run.getId(), progress);
        return runMapper.selectById(run.getId());
    }

    // ---------- 内部 ----------

    /** 必填变量缺失同步报 6009；未声明的输入变量静默忽略（宁简勿严） */
    private void validateRequiredInputs(Long workflowId, Map<String, String> inputs) {
        WorkflowNode startNode = nodeMapper.selectByWorkflow(workflowId).stream()
                .filter(n -> WorkflowNode.TYPE_START.equals(n.getNodeType()))
                .findFirst()
                .orElseThrow(() -> new BizException(WorkflowErrorCode.WORKFLOW_GRAPH_INVALID,
                        "工作流缺少 start 节点，无法运行"));
        JsonNode variables = readConfig(startNode).path("variables");
        if (!variables.isArray()) {
            return;
        }
        Set<String> missing = new HashSet<>();
        for (JsonNode variable : variables) {
            String name = variable.path("name").asText("");
            if (!name.isEmpty() && variable.path("required").asBoolean(false)
                    && (inputs.get(name) == null || inputs.get(name).isBlank())) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_RUN_INPUT_INVALID,
                    "缺少必填的起点变量：" + String.join("、", missing));
        }
    }

    private void markFailed(Long runId, String message) {
        WorkflowRun update = new WorkflowRun();
        update.setId(runId);
        update.setStatus(WorkflowRun.STATUS_FAILED);
        update.setErrorMsg(message);
        runMapper.updateById(update);
    }

    private JsonNode readConfig(WorkflowNode node) {
        try {
            String config = node.getConfig();
            if (config == null || config.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(config);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private Map<String, String> parseInputs(WorkflowRun run) {
        try {
            String inputs = run.getInputs();
            if (inputs == null || inputs.isBlank()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(inputs,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private List<NodeTrace> parseTraces(WorkflowRun run) {
        try {
            String traces = run.getNodeTraces();
            if (traces == null || traces.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(traces,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, NodeTrace.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
