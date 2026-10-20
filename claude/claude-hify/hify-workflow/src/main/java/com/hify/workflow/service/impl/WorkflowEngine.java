package com.hify.workflow.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.http.LlmApiException;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderHttpService;
import com.hify.provider.service.ProviderService;
import com.hify.workflow.dto.NodeTrace;
import com.hify.workflow.entity.WorkflowEdge;
import com.hify.workflow.entity.WorkflowNode;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.exception.WorkflowExecutionException;
import com.hify.workflow.mapper.WorkflowEdgeMapper;
import com.hify.workflow.mapper.WorkflowNodeMapper;
import com.hify.workflow.mapper.WorkflowRunMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作流执行引擎：从 start 沿边游走到 end。
 *
 * <ul>
 *   <li>变量池：start 节点每个输入变量存 {@code start节点Key.变量名}，
 *       LLM 节点输出存 {@code 节点Key.output}，模板 / 条件表达式按此引用</li>
 *   <li>condition 节点出边按 id 顺序求值取首个命中，always 为兜底；无命中即失败</li>
 *   <li>步数上限防条件成环死循环（图校验只保证连通，不禁止环）</li>
 *   <li>所有失败归一为 failed 运行记录 + 节点级 trace，不向外抛</li>
 * </ul>
 *
 * <p>调用方（WorkflowRunServiceImpl）保证在 llmExecutor 上执行（LLM 调用线程池规则）。
 */
@Slf4j
@Component
public class WorkflowEngine {

    /** 模板 / 表达式里的变量引用，如 ${llm1.output} */
    private static final Pattern REF = Pattern.compile("\\$\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}");

    private final WorkflowNodeMapper nodeMapper;

    private final WorkflowEdgeMapper edgeMapper;

    private final WorkflowRunMapper runMapper;

    private final ModelConfigService modelConfigService;

    private final ProviderService providerService;

    private final LlmNodeClient llmNodeClient;

    private final ConditionEvaluator conditionEvaluator;

    private final ObjectMapper objectMapper;

    /** 单次运行最大节点执行步数，防死循环 */
    @Value("${hify.workflow.max-steps:50}")
    private int maxSteps;

    /** trace 里 input/output 的截断长度，防 TEXT 列超限 */
    @Value("${hify.workflow.trace-max-chars:2000}")
    private int traceMaxChars;

    public WorkflowEngine(
            WorkflowNodeMapper nodeMapper,
            WorkflowEdgeMapper edgeMapper,
            WorkflowRunMapper runMapper,
            ModelConfigService modelConfigService,
            ProviderService providerService,
            LlmNodeClient llmNodeClient,
            ConditionEvaluator conditionEvaluator,
            ObjectMapper objectMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.runMapper = runMapper;
        this.modelConfigService = modelConfigService;
        this.providerService = providerService;
        this.llmNodeClient = llmNodeClient;
        this.conditionEvaluator = conditionEvaluator;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次运行（异步，由 llmExecutor 线程调进来）。任何失败都归一为
     * failed 状态的运行记录，不抛出。
     */
    public void execute(Long runId) {
        doExecute(runId, null);
    }

    /**
     * 带进度回调执行（对话触发时把节点执行进度推给 SSE）。
     *
     * @param progress 每个节点执行前回调一行进度描述，可为 null
     */
    public void execute(Long runId, java.util.function.Consumer<String> progress) {
        if (progress != null) {
            progress.accept("工作流开始执行");
        }
        try {
            doExecute(runId, progress);
        } finally {
            if (progress != null) {
                progress.accept("工作流执行结束");
            }
        }
    }

    private void doExecute(Long runId, java.util.function.Consumer<String> progress) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            log.warn("运行记录不存在，忽略执行 runId={}", runId);
            return;
        }
        long start = System.currentTimeMillis();
        List<NodeTrace> traces = new ArrayList<>();
        Map<String, String> pool = new LinkedHashMap<>();
        String output = null;
        String errorMsg = null;
        try {
            Map<String, String> inputs = parseInputs(run);
            output = walk(run.getWorkflowId(), inputs, pool, traces, progress);
        } catch (WorkflowExecutionException e) {
            errorMsg = e.getMessage();
        } catch (Exception e) {
            // 引擎自身缺陷（解析失败等），也归一进运行记录便于排障
            log.error("工作流执行异常 runId={}", runId, e);
            errorMsg = "工作流执行异常：" + e.getMessage();
        }
        finish(run, output, errorMsg, traces, (int) (System.currentTimeMillis() - start));
    }

    // ---------- 图游走 ----------

    /** 从 start 走到 end，返回 end 节点渲染后的最终输出 */
    private String walk(Long workflowId, Map<String, String> inputs,
            Map<String, String> pool, List<NodeTrace> traces,
            java.util.function.Consumer<String> progress) {
        List<WorkflowNode> nodes = nodeMapper.selectByWorkflow(workflowId);
        List<WorkflowEdge> edges = edgeMapper.selectByWorkflow(workflowId);
        if (nodes.isEmpty()) {
            throw new WorkflowExecutionException("工作流不存在或已被删除");
        }
        Map<String, WorkflowNode> byKey = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            byKey.put(node.getNodeKey(), node);
        }
        Map<String, List<WorkflowEdge>> outgoing = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getSourceNodeKey(), k -> new ArrayList<>()).add(edge);
        }

        WorkflowNode startNode = nodes.stream()
                .filter(n -> WorkflowNode.TYPE_START.equals(n.getNodeType()))
                .findFirst()
                .orElseThrow(() -> new WorkflowExecutionException("工作流缺少 start 节点"));
        WorkflowNode current = startNode;
        int steps = 0;
        while (true) {
            steps++;
            if (steps > maxSteps) {
                throw new WorkflowExecutionException(
                        "执行步数超过上限 " + maxSteps + "，疑似存在死循环");
            }
            if (progress != null) {
                progress.accept("执行节点「" + current.getName() + "」(" + current.getNodeType() + ")");
            }
            current = executeNode(current, inputs, pool, traces, byKey);
            if (current == null) {
                // executeNode 返回 null 表示走到 end 节点并已完成输出
                return traces.get(traces.size() - 1).getOutput();
            }
            WorkflowEdge next = pickEdge(current, outgoing, pool, traces);
            if (next == null) {
                if (WorkflowNode.TYPE_CONDITION.equals(current.getNodeType())) {
                    throw new WorkflowExecutionException(
                            "条件节点「" + current.getName() + "」无命中分支（各条件表达式均不满足且无兜底边）");
                }
                throw new WorkflowExecutionException("节点「" + current.getName() + "」缺少出边");
            }
            WorkflowNode target = byKey.get(next.getTargetNodeKey());
            if (target == null) {
                throw new WorkflowExecutionException(
                        "连线指向不存在的节点：" + next.getTargetNodeKey());
            }
            current = target;
        }
    }

    /** 执行单个节点并记录 trace；走到 end 时返回 null，否则返回原节点（供选边）。失败也入列再抛 */
    private WorkflowNode executeNode(WorkflowNode node, Map<String, String> inputs,
            Map<String, String> pool, List<NodeTrace> traces, Map<String, WorkflowNode> byKey) {
        long nodeStart = System.currentTimeMillis();
        NodeTrace trace = new NodeTrace();
        trace.setNodeKey(node.getNodeKey());
        trace.setNodeType(node.getNodeType());
        trace.setName(node.getName());
        trace.setInput("");
        trace.setOutput("");
        try {
            switch (node.getNodeType()) {
                case WorkflowNode.TYPE_START -> executeStart(node, inputs, pool, trace);
                case WorkflowNode.TYPE_LLM -> executeLlm(node, pool, trace);
                case WorkflowNode.TYPE_CONDITION -> trace.setInput("分支路由见下一条 trace");
                case WorkflowNode.TYPE_END -> {
                    JsonNode config = readConfig(node);
                    String template = config.path("outputTemplate").asText("");
                    if (template.isBlank()) {
                        throw new WorkflowExecutionException(
                                "end 节点「" + node.getName() + "」未配置输出模板");
                    }
                    trace.setOutput(resolveTemplate(template, pool));
                    trace.setLatencyMs((int) (System.currentTimeMillis() - nodeStart));
                    traces.add(trace);
                    return null;
                }
                default -> throw new WorkflowExecutionException(
                        "不支持的节点类型：" + node.getNodeType());
            }
        } catch (WorkflowExecutionException e) {
            // 失败节点也要留轨迹（排障关键），入列后继续向上抛
            trace.setError(e.getMessage());
            trace.setLatencyMs((int) (System.currentTimeMillis() - nodeStart));
            traces.add(trace);
            throw e;
        }
        trace.setLatencyMs((int) (System.currentTimeMillis() - nodeStart));
        traces.add(trace);
        return node;
    }

    /** start：把输入变量放进变量池（必填缺失已在提交前同步校验，这里兜底） */
    private void executeStart(WorkflowNode node, Map<String, String> inputs,
            Map<String, String> pool, NodeTrace trace) {
        JsonNode variables = readConfig(node).path("variables");
        if (variables.isArray()) {
            for (JsonNode variable : variables) {
                String name = variable.path("name").asText("");
                if (name.isEmpty()) {
                    continue;
                }
                String value = inputs.getOrDefault(name, "");
                if (variable.path("required").asBoolean(false) && value.isBlank()) {
                    throw new WorkflowExecutionException("缺少必填的起点变量：" + name);
                }
                pool.put(node.getNodeKey() + "." + name, value);
            }
        }
        trace.setInput(inputs.toString());
    }

    /** llm：渲染 prompt 模板 → 非流式调用 → 输出入变量池 */
    private void executeLlm(WorkflowNode node, Map<String, String> pool, NodeTrace trace) {
        JsonNode config = readConfig(node);
        long modelConfigId = config.path("modelConfigId").asLong(0);
        String promptTemplate = config.path("prompt").asText("");
        if (modelConfigId <= 0 || promptTemplate.isBlank()) {
            throw new WorkflowExecutionException(
                    "LLM 节点「" + node.getName() + "」缺少模型或 prompt 配置");
        }
        String prompt = resolveTemplate(promptTemplate, pool);
        trace.setInput(truncate(prompt));

        ModelConfig model = modelConfigService.getById(modelConfigId);
        if (model == null || model.getEnabled() != 1
                || !"chat".equals(model.getModelType())) {
            throw new WorkflowExecutionException(
                    "LLM 节点「" + node.getName() + "」绑定的模型不可用，id=" + modelConfigId);
        }
        Provider provider = providerService.getEntity(model.getProviderId());
        if (provider == null || provider.getEnabled() != 1) {
            throw new WorkflowExecutionException(
                    "LLM 节点「" + node.getName() + "」绑定的提供商不可用：" + modelConfigId);
        }
        BigDecimal temperature = config.hasNonNull("temperature")
                ? config.get("temperature").decimalValue() : null;
        int maxTokens = config.path("maxTokens").asInt(0);
        try {
            String output = llmNodeClient.chat(provider, model, prompt, temperature, maxTokens);
            pool.put(node.getNodeKey() + ".output", output);
            trace.setOutput(truncate(output));
        } catch (LlmApiException e) {
            // 描述文案与对话链路保持一致（超时/认证/限流分类）
            throw new WorkflowExecutionException(
                    "LLM 节点「" + node.getName() + "」调用失败：" + ProviderHttpService.describeFailure(e));
        }
    }

    /** 选下一条出边：condition 节点按序求值（always 兜底），其余节点取唯一 always 边 */
    private WorkflowEdge pickEdge(WorkflowNode node, Map<String, List<WorkflowEdge>> outgoing,
            Map<String, String> pool, List<NodeTrace> traces) {
        List<WorkflowEdge> edges = outgoing.getOrDefault(node.getNodeKey(), List.of());
        if (!WorkflowNode.TYPE_CONDITION.equals(node.getNodeType())) {
            return edges.isEmpty() ? null : edges.get(0);
        }
        WorkflowEdge fallback = null;
        for (WorkflowEdge edge : edges) {
            if (WorkflowEdge.CONDITION_ALWAYS.equals(edge.getConditionType())) {
                fallback = edge;
                continue;
            }
            String expr = edge.getConditionExpr() == null ? "" : edge.getConditionExpr();
            try {
                if (conditionEvaluator.evaluate(expr, pool)) {
                    // 把路由结果记在 condition 节点的 trace 上（该 trace 已入列，回填）
                    NodeTrace trace = traces.get(traces.size() - 1);
                    trace.setOutput("命中分支 → " + edge.getTargetNodeKey() + "（" + expr + "）");
                    return edge;
                }
            } catch (com.hify.common.exception.BizException e) {
                throw new WorkflowExecutionException("条件表达式求值失败：" + e.getMessage());
            }
        }
        if (fallback != null) {
            NodeTrace trace = traces.get(traces.size() - 1);
            trace.setOutput("兜底分支 → " + fallback.getTargetNodeKey());
        }
        return fallback;
    }

    // ---------- 模板 ----------

    /** 渲染 ${ref}：引用未定义的变量即失败（宁停勿猜） */
    private String resolveTemplate(String template, Map<String, String> pool) {
        Matcher matcher = REF.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = pool.get(key);
            if (value == null) {
                throw new WorkflowExecutionException("引用了未定义的变量 ${" + key + "}");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // ---------- 运行记录收尾 ----------

    private void finish(WorkflowRun run, String output, String errorMsg,
            List<NodeTrace> traces, int latencyMs) {
        WorkflowRun update = new WorkflowRun();
        update.setId(run.getId());
        update.setStatus(errorMsg == null ? WorkflowRun.STATUS_SUCCEEDED : WorkflowRun.STATUS_FAILED);
        update.setOutput(output == null ? "" : output);
        update.setErrorMsg(errorMsg == null ? "" : errorMsg);
        update.setNodeTraces(writeTraces(traces));
        update.setLatencyMs(latencyMs);
        runMapper.updateById(update);
        log.info("工作流运行完成 runId={} workflowId={} status={} 耗时={}ms 步数={}",
                run.getId(), run.getWorkflowId(), update.getStatus(), latencyMs, traces.size());
    }

    // ---------- 内部 ----------

    private JsonNode readConfig(WorkflowNode node) {
        try {
            String config = node.getConfig();
            if (config == null || config.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(config);
        } catch (Exception e) {
            throw new WorkflowExecutionException(
                    "节点「" + node.getName() + "」配置不是合法 JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseInputs(WorkflowRun run) {
        try {
            String inputs = run.getInputs();
            if (inputs == null || inputs.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(inputs, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeTraces(List<NodeTrace> traces) {
        try {
            return objectMapper.writeValueAsString(traces);
        } catch (Exception e) {
            log.warn("轨迹序列化失败 runId 落库空轨迹", e);
            return "[]";
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > traceMaxChars ? text.substring(0, traceMaxChars) + "…" : text;
    }
}
