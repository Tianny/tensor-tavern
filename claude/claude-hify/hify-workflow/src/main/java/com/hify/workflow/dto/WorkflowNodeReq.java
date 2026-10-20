package com.hify.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 工作流节点保存请求。config 直接透传 JSON（结构按 nodeType 约定，
 * 见 WorkflowNode 类注释），Service 层按类型做结构与语义校验。
 */
@Data
public class WorkflowNodeReq {

    /** 节点标识，工作流内唯一，连线按此引用；字母开头，可含数字/下划线 */
    @NotBlank(message = "nodeKey 不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{0,63}$",
            message = "nodeKey 须以字母开头，仅含字母/数字/下划线，最长 64 字符")
    private String nodeKey;

    @NotBlank(message = "节点类型不能为空")
    @Pattern(regexp = "^(start|llm|condition|end)$", message = "节点类型仅支持 start/llm/condition/end")
    private String nodeType;

    @NotBlank(message = "节点名称不能为空")
    @Size(max = 64, message = "节点名称最长 64 字")
    private String name;

    /** 节点配置 JSON，缺省为空对象 */
    private JsonNode config;
}
