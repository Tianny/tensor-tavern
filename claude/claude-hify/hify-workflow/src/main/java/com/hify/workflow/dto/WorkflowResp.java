package com.hify.workflow.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.hify.workflow.entity.Workflow;
import com.hify.workflow.entity.WorkflowEdge;
import com.hify.workflow.entity.WorkflowNode;

import lombok.Data;

/**
 * 工作流详情响应（含全量节点 / 连线）。
 */
@Data
public class WorkflowResp {

    private Long id;

    private String name;

    private String description;

    private Integer enabled;

    private List<NodeItem> nodes;

    private List<EdgeItem> edges;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 节点（config 原样 JSON 返回，前端按类型渲染表单） */
    @Data
    public static class NodeItem {

        private Long id;

        private String nodeKey;

        private String nodeType;

        private String name;

        private JsonNode config;

        public static NodeItem of(WorkflowNode node, JsonNode config) {
            NodeItem item = new NodeItem();
            item.setId(node.getId());
            item.setNodeKey(node.getNodeKey());
            item.setNodeType(node.getNodeType());
            item.setName(node.getName());
            item.setConfig(config);
            return item;
        }
    }

    /** 连线 */
    @Data
    public static class EdgeItem {

        private Long id;

        private String sourceNodeKey;

        private String targetNodeKey;

        private String conditionType;

        private String conditionExpr;

        public static EdgeItem of(WorkflowEdge edge) {
            EdgeItem item = new EdgeItem();
            item.setId(edge.getId());
            item.setSourceNodeKey(edge.getSourceNodeKey());
            item.setTargetNodeKey(edge.getTargetNodeKey());
            item.setConditionType(edge.getConditionType());
            item.setConditionExpr(edge.getConditionExpr() == null ? "" : edge.getConditionExpr());
            return item;
        }
    }

    public static WorkflowResp of(Workflow wf, List<NodeItem> nodes, List<EdgeItem> edges) {
        WorkflowResp resp = new WorkflowResp();
        resp.setId(wf.getId());
        resp.setName(wf.getName());
        resp.setDescription(wf.getDescription() == null ? "" : wf.getDescription());
        resp.setEnabled(wf.getEnabled());
        resp.setNodes(nodes);
        resp.setEdges(edges);
        resp.setCreatedAt(wf.getCreatedAt());
        resp.setUpdatedAt(wf.getUpdatedAt());
        return resp;
    }
}
