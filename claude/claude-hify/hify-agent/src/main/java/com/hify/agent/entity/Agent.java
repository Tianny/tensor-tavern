package com.hify.agent.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 配置：绑模型、System Prompt、采样参数。
 * 绑定 MCP 工具（agent_tool）在 hify-mcp 模块落地后实现。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
public class Agent extends BaseEntity {

    /** Agent 名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 绑定的模型配置 model_config.id */
    private Long modelConfigId;

    /** System Prompt */
    private String systemPrompt;

    /** 采样温度 0.00-2.00 */
    private BigDecimal temperature;

    /** 单次回复 token 上限，0=用模型默认 */
    private Integer maxTokens;

    /** 是否启用：0=停用 1=启用 */
    private Integer enabled;
}
