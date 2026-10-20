package com.hify.workflow.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderService;
import com.hify.workflow.constant.WorkflowErrorCode;
import com.hify.workflow.dto.WorkflowEdgeReq;
import com.hify.workflow.dto.WorkflowNodeReq;
import com.hify.workflow.dto.WorkflowResp;
import com.hify.workflow.dto.WorkflowSaveReq;
import com.hify.workflow.entity.Workflow;
import com.hify.workflow.entity.WorkflowEdge;
import com.hify.workflow.entity.WorkflowNode;
import com.hify.workflow.mapper.WorkflowEdgeMapper;
import com.hify.workflow.mapper.WorkflowNodeMapper;
import com.hify.workflow.mapper.WorkflowRunMapper;
import com.hify.workflow.mapper.WorkflowMapper;
import com.hify.workflow.service.WorkflowService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作流管理。保存 = 整体图替换（节点 / 连线物理删除重插，纯派生数据）。
 * 保存时做完整图校验：结构（start/end/出边/连通性）+ 语义（LLM 模型可用、
 * 模板引用可解析、条件表达式语法），把问题拦在运行前。
 */
@Slf4j
@Service
public class WorkflowServiceImpl implements WorkflowService {

    /** LLM 节点支持的提供商类型（非流式端点实现范围） */
    private static final Set<String> LLM_SUPPORTED_TYPES = Set.of("openai", "openai_compatible", "claude");

    /** 变量 / nodeKey 命名规则（nodeKey 同名校验，变量名不含点） */
    private static final String NAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9_]{0,63}$";

    private static final int MAX_VARIABLES = 16;

    private static final int MAX_PROMPT_CHARS = 8000;

    private static final int MAX_OUTPUT_TEMPLATE_CHARS = 2000;

    private final WorkflowMapper workflowMapper;

    private final WorkflowNodeMapper nodeMapper;

    private final WorkflowEdgeMapper edgeMapper;

    private final WorkflowRunMapper runMapper;

    private final ModelConfigService modelConfigService;

    private final ProviderService providerService;

    private final ConditionEvaluator conditionEvaluator;

    private final ObjectMapper objectMapper;

    public WorkflowServiceImpl(
            WorkflowMapper workflowMapper,
            WorkflowNodeMapper nodeMapper,
            WorkflowEdgeMapper edgeMapper,
            WorkflowRunMapper runMapper,
            ModelConfigService modelConfigService,
            ProviderService providerService,
            ConditionEvaluator conditionEvaluator,
            ObjectMapper objectMapper) {
        this.workflowMapper = workflowMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.runMapper = runMapper;
        this.modelConfigService = modelConfigService;
        this.providerService = providerService;
        this.conditionEvaluator = conditionEvaluator;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<WorkflowResp> list() {
        List<Workflow> workflows = workflowMapper.selectList(
                new LambdaQueryWrapper<Workflow>().orderByDesc(Workflow::getId));
        if (workflows.isEmpty()) {
            return List.of();
        }
        // 两趟批量查节点 / 连线，组装全量图
        List<Long> ids = workflows.stream().map(Workflow::getId).toList();
        Map<Long, List<WorkflowNode>> nodes = nodeMapper.selectList(
                        new LambdaQueryWrapper<WorkflowNode>().in(WorkflowNode::getWorkflowId, ids)
                                .orderByAsc(WorkflowNode::getId))
                .stream().collect(Collectors.groupingBy(WorkflowNode::getWorkflowId));
        Map<Long, List<WorkflowEdge>> edges = edgeMapper.selectList(
                        new LambdaQueryWrapper<WorkflowEdge>().in(WorkflowEdge::getWorkflowId, ids)
                                .orderByAsc(WorkflowEdge::getId))
                .stream().collect(Collectors.groupingBy(WorkflowEdge::getWorkflowId));
        return workflows.stream()
                .map(wf -> buildResp(wf,
                        nodes.getOrDefault(wf.getId(), List.of()),
                        edges.getOrDefault(wf.getId(), List.of())))
                .toList();
    }

    @Override
    public WorkflowResp getDetail(Long id) {
        Workflow wf = getEntity(id);
        return buildResp(wf,
                nodeMapper.selectByWorkflow(id),
                edgeMapper.selectByWorkflow(id));
    }

    @Override
    @Transactional
    public WorkflowResp create(WorkflowSaveReq req) {
        checkNameDuplicate(req.getName(), null);
        Workflow wf = new Workflow();
        wf.setName(req.getName().trim());
        wf.setDescription(req.getDescription() == null ? "" : req.getDescription().trim());
        wf.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        workflowMapper.insert(wf);
        saveGraph(wf.getId(), req);
        return getResp(wf.getId());
    }

    @Override
    @Transactional
    public WorkflowResp update(Long id, WorkflowSaveReq req) {
        getEntity(id);
        checkNameDuplicate(req.getName(), id);
        Workflow update = new Workflow();
        update.setId(id);
        update.setName(req.getName().trim());
        update.setDescription(req.getDescription() == null ? "" : req.getDescription().trim());
        update.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        workflowMapper.updateById(update);
        // 整体图替换
        nodeMapper.physicalDeleteByWorkflow(id);
        edgeMapper.physicalDeleteByWorkflow(id);
        saveGraph(id, req);
        return getResp(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getEntity(id);
        workflowMapper.deleteById(id);
        nodeMapper.physicalDeleteByWorkflow(id);
        edgeMapper.physicalDeleteByWorkflow(id);
        runMapper.physicalDeleteByWorkflow(id);
    }

    @Override
    public Workflow getEntity(Long id) {
        Workflow wf = workflowMapper.selectById(id);
        if (wf == null) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_NOT_FOUND, "工作流不存在，id=" + id);
        }
        return wf;
    }

    @Override
    public String chatEntryVariable(Long workflowId) {
        getEntity(workflowId);
        WorkflowNode startNode = nodeMapper.selectByWorkflow(workflowId).stream()
                .filter(n -> WorkflowNode.TYPE_START.equals(n.getNodeType()))
                .findFirst()
                .orElseThrow(() -> new BizException(WorkflowErrorCode.WORKFLOW_CHAT_ENTRY_MISSING,
                        "工作流缺少 start 节点，无法对话触发"));
        JsonNode variables = readConfig(startNode.getConfig()).path("variables");
        if (!variables.isArray() || variables.isEmpty()) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_CHAT_ENTRY_MISSING,
                    "工作流 start 节点未声明输入变量，用户消息无处填入，工作流名=" + startNode.getName());
        }
        String first = variables.get(0).path("name").asText("");
        for (int i = 1; i < variables.size(); i++) {
            if (variables.get(i).path("required").asBoolean(false)) {
                throw new BizException(WorkflowErrorCode.WORKFLOW_CHAT_ENTRY_MISSING,
                        "start 变量「" + variables.get(i).path("name").asText()
                                + "」为必填，但对话界面只能填入第一个变量，请改为选填或删除");
            }
        }
        return first;
    }

    // ---------- 图保存与校验 ----------

    private void saveGraph(Long workflowId, WorkflowSaveReq req) {
        // 先整体校验（结构 + 语义），通过才落库
        Map<String, WorkflowNodeReq> nodesByKey = validateGraph(req);

        for (WorkflowNodeReq nodeReq : req.getNodes()) {
            WorkflowNode node = new WorkflowNode();
            node.setWorkflowId(workflowId);
            node.setNodeKey(nodeReq.getNodeKey());
            node.setNodeType(nodeReq.getNodeType());
            node.setName(nodeReq.getName().trim());
            node.setConfig(nodeReq.getConfig() == null ? "{}"
                    : nodeReq.getConfig().toString());
            nodeMapper.insert(node);
        }
        for (WorkflowEdgeReq edgeReq : req.getEdges()) {
            WorkflowEdge edge = new WorkflowEdge();
            edge.setWorkflowId(workflowId);
            edge.setSourceNodeKey(edgeReq.getSourceNodeKey());
            edge.setTargetNodeKey(edgeReq.getTargetNodeKey());
            edge.setConditionType(edgeReq.getConditionType());
            edge.setConditionExpr(WorkflowEdge.CONDITION_EXPRESSION.equals(edgeReq.getConditionType())
                    ? edgeReq.getConditionExpr().trim() : "");
            edgeMapper.insert(edge);
        }
    }

    /**
     * 图校验（结构 + 节点语义 + 连线语义 + 引用可达），返回 nodeKey → 请求节点映射。
     */
    private Map<String, WorkflowNodeReq> validateGraph(WorkflowSaveReq req) {
        // 1. nodeKey 唯一 + 类型语义校验
        Map<String, WorkflowNodeReq> nodesByKey = new HashMap<>();
        for (WorkflowNodeReq node : req.getNodes()) {
            if (nodesByKey.put(node.getNodeKey(), node) != null) {
                throw nodeError("nodeKey 重复：" + node.getNodeKey());
            }
            validateNodeConfig(node);
        }

        // 2. start / end 结构
        long startCount = req.getNodes().stream()
                .filter(n -> WorkflowNode.TYPE_START.equals(n.getNodeType())).count();
        if (startCount != 1) {
            throw graphError("必须有且只有一个 start 节点");
        }
        boolean hasEnd = req.getNodes().stream()
                .anyMatch(n -> WorkflowNode.TYPE_END.equals(n.getNodeType()));
        if (!hasEnd) {
            throw graphError("至少要有一个 end 节点");
        }

        // 3. 连线校验
        Set<String> edgePairs = new HashSet<>();
        Map<String, List<WorkflowEdgeReq>> outgoing = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        int alwaysOutPerCondition = 0;
        for (WorkflowEdgeReq edge : req.getEdges()) {
            WorkflowNodeReq source = nodesByKey.get(edge.getSourceNodeKey());
            if (source == null) {
                throw graphError("连线起点不存在：" + edge.getSourceNodeKey());
            }
            if (!nodesByKey.containsKey(edge.getTargetNodeKey())) {
                throw graphError("连线终点不存在：" + edge.getTargetNodeKey());
            }
            if (edge.getSourceNodeKey().equals(edge.getTargetNodeKey())) {
                throw graphError("连线不能自环：" + edge.getSourceNodeKey());
            }
            if (!edgePairs.add(edge.getSourceNodeKey() + "→" + edge.getTargetNodeKey())) {
                throw graphError("重复连线：" + edge.getSourceNodeKey() + "→" + edge.getTargetNodeKey());
            }
            outgoing.computeIfAbsent(edge.getSourceNodeKey(), k -> new ArrayList<>()).add(edge);
            incoming.merge(edge.getTargetNodeKey(), 1, Integer::sum);

            boolean fromCondition = WorkflowNode.TYPE_CONDITION.equals(source.getNodeType());
            boolean isExpression = WorkflowEdge.CONDITION_EXPRESSION.equals(edge.getConditionType());
            if (fromCondition) {
                if (isExpression) {
                    if (edge.getConditionExpr() == null || edge.getConditionExpr().isBlank()) {
                        throw graphError("表达式连线缺少条件：" + edge.getSourceNodeKey() + "→" + edge.getTargetNodeKey());
                    }
                    conditionEvaluator.validateSyntax(edge.getConditionExpr().trim());
                } else {
                    alwaysOutPerCondition++;
                }
            } else if (isExpression) {
                // 条件表达式只有 condition 节点出边有意义
                throw graphError("只有 condition 节点的出边可以配条件表达式："
                        + edge.getSourceNodeKey() + "→" + edge.getTargetNodeKey());
            }
        }
        // always 兜底边全局只允许一条（多个 condition 节点各自兜底会互相掩盖语义）
        if (alwaysOutPerCondition > 1) {
            throw graphError("always 兜底连线最多一条");
        }

        // 4. 出入边约束
        for (WorkflowNodeReq node : req.getNodes()) {
            int out = outgoing.getOrDefault(node.getNodeKey(), List.of()).size();
            int in = incoming.getOrDefault(node.getNodeKey(), 0);
            switch (node.getNodeType()) {
                case WorkflowNode.TYPE_START -> {
                    if (in > 0) {
                        throw graphError("start 节点不能有入边");
                    }
                    if (out != 1) {
                        throw graphError("start 节点必须有一条出边");
                    }
                }
                case WorkflowNode.TYPE_LLM -> {
                    if (out != 1) {
                        throw graphError("LLM 节点「" + node.getName() + "」必须有一条出边");
                    }
                    if (in == 0) {
                        throw graphError("LLM 节点「" + node.getName() + "」必须有入边");
                    }
                }
                case WorkflowNode.TYPE_CONDITION -> {
                    if (out < 1) {
                        throw graphError("条件节点「" + node.getName() + "」至少要有一条出边");
                    }
                    if (in == 0) {
                        throw graphError("条件节点「" + node.getName() + "」必须有入边");
                    }
                }
                case WorkflowNode.TYPE_END -> {
                    if (out > 0) {
                        throw graphError("end 节点不能有出边");
                    }
                    if (in == 0) {
                        throw graphError("end 节点必须有入边");
                    }
                }
                default -> throw nodeError("不支持的节点类型：" + node.getNodeType());
            }
        }

        // 5. start 可达所有节点（不可达节点即配置残留）
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        String startKey = req.getNodes().stream()
                .filter(n -> WorkflowNode.TYPE_START.equals(n.getNodeType()))
                .findFirst().orElseThrow().getNodeKey();
        queue.add(startKey);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!visited.add(key)) {
                continue;
            }
            for (WorkflowEdgeReq edge : outgoing.getOrDefault(key, List.of())) {
                queue.add(edge.getTargetNodeKey());
            }
        }
        for (WorkflowNodeReq node : req.getNodes()) {
            if (!visited.contains(node.getNodeKey())) {
                throw graphError("节点不可达（从 start 无法沿连线到达）："
                        + node.getNodeKey() + "「" + node.getName() + "」");
            }
        }

        // 6. 模板引用静态校验（${key}：节点存在 + 字段名正确）
        validateReferences(req, nodesByKey);
        return nodesByKey;
    }

    /** 按节点类型校验 config 语义 */
    private void validateNodeConfig(WorkflowNodeReq node) {
        JsonNode config = node.getConfig() == null ? objectMapper.createObjectNode() : node.getConfig();
        switch (node.getNodeType()) {
            case WorkflowNode.TYPE_START -> {
                JsonNode variables = config.path("variables");
                if (!variables.isArray()) {
                    return;   // 未声明输入变量，合法（无输入工作流）
                }
                Set<String> names = new HashSet<>();
                if (variables.size() > MAX_VARIABLES) {
                    throw nodeError("start 节点变量最多 " + MAX_VARIABLES + " 个");
                }
                for (JsonNode variable : variables) {
                    String name = variable.path("name").asText("");
                    if (!name.matches(NAME_PATTERN)) {
                        throw nodeError("变量名不合法（字母开头，仅字母/数字/下划线）：" + name);
                    }
                    if (!names.add(name)) {
                        throw nodeError("变量名重复：" + name);
                    }
                }
            }
            case WorkflowNode.TYPE_LLM -> {
                long modelConfigId = config.path("modelConfigId").asLong(0);
                if (modelConfigId <= 0) {
                    throw nodeError("LLM 节点「" + node.getName() + "」未配置模型");
                }
                String prompt = config.path("prompt").asText("");
                if (prompt.isBlank()) {
                    throw nodeError("LLM 节点「" + node.getName() + "」未配置 prompt");
                }
                if (prompt.length() > MAX_PROMPT_CHARS) {
                    throw nodeError("LLM 节点「" + node.getName() + "」prompt 最长 " + MAX_PROMPT_CHARS + " 字");
                }
                JsonNode temperature = config.path("temperature");
                if (temperature.isNumber() && temperature.decimalValue().doubleValue() > 2) {
                    throw nodeError("LLM 节点「" + node.getName() + "」温度范围 0-2");
                }
                validateLlmModel(node, modelConfigId);
            }
            case WorkflowNode.TYPE_CONDITION -> {
                // 条件在出边上，节点自身无 config 约束
            }
            case WorkflowNode.TYPE_END -> {
                String template = config.path("outputTemplate").asText("");
                if (template.isBlank()) {
                    throw nodeError("end 节点「" + node.getName() + "」未配置输出模板");
                }
                if (template.length() > MAX_OUTPUT_TEMPLATE_CHARS) {
                    throw nodeError("end 节点「" + node.getName() + "」输出模板最长 "
                            + MAX_OUTPUT_TEMPLATE_CHARS + " 字");
                }
            }
            default -> throw nodeError("不支持的节点类型：" + node.getNodeType());
        }
    }

    /** LLM 节点模型校验：存在、启用、chat 类型、提供商可用且类型受支持 */
    private void validateLlmModel(WorkflowNodeReq node, long modelConfigId) {
        ModelConfig model = modelConfigService.getById(modelConfigId);
        if (model == null || model.getEnabled() != 1 || !"chat".equals(model.getModelType())) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_LLM_MODEL_INVALID,
                    "LLM 节点「" + node.getName() + "」模型不可用或不是 chat 类型，id=" + modelConfigId);
        }
        Provider provider = providerService.getEntity(model.getProviderId());
        if (provider == null || provider.getEnabled() != 1
                || !LLM_SUPPORTED_TYPES.contains(provider.getType())) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_LLM_MODEL_INVALID,
                    "LLM 节点「" + node.getName() + "」提供商不可用或类型不支持，id=" + modelConfigId);
        }
    }

    /** 模板 / 表达式里 ${key} 的静态引用校验：key = 节点Key.字段，节点须存在且字段合法 */
    private void validateReferences(WorkflowSaveReq req, Map<String, WorkflowNodeReq> nodesByKey) {
        for (WorkflowNodeReq node : req.getNodes()) {
            JsonNode config = node.getConfig() == null ? objectMapper.createObjectNode() : node.getConfig();
            List<String> templates = new ArrayList<>();
            if (WorkflowNode.TYPE_LLM.equals(node.getNodeType())) {
                templates.add(config.path("prompt").asText(""));
            } else if (WorkflowNode.TYPE_END.equals(node.getNodeType())) {
                templates.add(config.path("outputTemplate").asText(""));
            } else {
                continue;
            }
            for (String template : templates) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\\$\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}")
                        .matcher(template);
                while (matcher.find()) {
                    validateRefKey(matcher.group(1), node, nodesByKey);
                }
            }
        }
    }

    private void validateRefKey(String key, WorkflowNodeReq referencing, Map<String, WorkflowNodeReq> nodesByKey) {
        int dot = key.indexOf('.');
        if (dot <= 0 || dot == key.length() - 1) {
            throw nodeError("节点「" + referencing.getName() + "」的变量引用格式不合法：${" + key + "}");
        }
        String nodeKey = key.substring(0, dot);
        String field = key.substring(dot + 1);
        WorkflowNodeReq target = nodesByKey.get(nodeKey);
        if (target == null) {
            throw nodeError("节点「" + referencing.getName() + "」引用了不存在的节点：${" + key + "}");
        }
        if (WorkflowNode.TYPE_LLM.equals(target.getNodeType()) && !"output".equals(field)) {
            throw nodeError("LLM 节点只有 output 字段可引用：${" + key + "}");
        }
        if (WorkflowNode.TYPE_START.equals(target.getNodeType())
                && !declaredVariables(target).contains(field)) {
            throw nodeError("start 节点未声明变量 " + field + "：${" + key + "}");
        }
        if (WorkflowNode.TYPE_CONDITION.equals(target.getNodeType())
                || WorkflowNode.TYPE_END.equals(target.getNodeType())) {
            throw nodeError("condition / end 节点没有可引用的输出：${" + key + "}");
        }
    }

    private Set<String> declaredVariables(WorkflowNodeReq startNode) {
        Set<String> names = new HashSet<>();
        JsonNode variables = startNode.getConfig() == null ? objectMapper.createObjectNode()
                : startNode.getConfig().path("variables");
        if (variables.isArray()) {
            for (JsonNode variable : variables) {
                names.add(variable.path("name").asText(""));
            }
        }
        return names;
    }

    // ---------- 内部 ----------

    private void checkNameDuplicate(String name, Long excludeId) {
        Workflow existing = workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getName, name.trim())
                .ne(excludeId != null, Workflow::getId, excludeId)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_NAME_DUPLICATED, "工作流名称已存在：" + name);
        }
    }

    private WorkflowResp getResp(Long id) {
        Workflow wf = getEntity(id);
        return buildResp(wf,
                nodeMapper.selectByWorkflow(id),
                edgeMapper.selectByWorkflow(id));
    }

    private WorkflowResp buildResp(Workflow wf, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        List<WorkflowResp.NodeItem> nodeItems = nodes.stream()
                .map(node -> WorkflowResp.NodeItem.of(node, readConfig(node.getConfig())))
                .toList();
        List<WorkflowResp.EdgeItem> edgeItems = edges.stream()
                .map(WorkflowResp.EdgeItem::of)
                .toList();
        return WorkflowResp.of(wf, nodeItems, edgeItems);
    }

    private JsonNode readConfig(String config) {
        try {
            if (config == null || config.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(config);
        } catch (Exception e) {
            // 落库前已校验过 JSON 合法性，理论不可达；兜底空对象防响应失败
            return objectMapper.createObjectNode();
        }
    }

    private BizException nodeError(String detail) {
        return new BizException(WorkflowErrorCode.WORKFLOW_NODE_INVALID, detail);
    }

    private BizException graphError(String detail) {
        return new BizException(WorkflowErrorCode.WORKFLOW_GRAPH_INVALID, detail);
    }
}
