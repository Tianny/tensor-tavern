package com.hify.workflow.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建 / 更新工作流请求。整体图保存：nodes + edges 全量替换。
 */
@Data
public class WorkflowSaveReq {

    @NotBlank(message = "工作流名称不能为空")
    @Size(max = 64, message = "工作流名称最长 64 字")
    private String name;

    @Size(max = 255, message = "描述最长 255 字")
    private String description;

    private Integer enabled;

    @Valid
    @NotEmpty(message = "工作流至少要有一个节点")
    private List<WorkflowNodeReq> nodes;

    @Valid
    @NotEmpty(message = "工作流至少要有一条连线")
    private List<WorkflowEdgeReq> edges;
}
