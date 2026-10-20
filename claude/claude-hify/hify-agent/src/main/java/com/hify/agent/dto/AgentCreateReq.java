package com.hify.agent.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建 Agent 请求。
 */
@Data
public class AgentCreateReq {

    @NotBlank
    @Size(max = 64)
    private String name;

    @Size(max = 255)
    private String description;

    /** 绑定的模型配置 model_config.id（须为启用状态的 chat 模型） */
    @NotNull
    private Long modelConfigId;

    /** System Prompt，可空 */
    private String systemPrompt;

    /** 采样温度 0-2，可空默认 0.70 */
    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private BigDecimal temperature;

    /** 单次回复 token 上限，可空默认 0（用模型默认） */
    @Min(0)
    @Max(2000000)
    private Integer maxTokens;

    /** 是否启用，可空默认 1 */
    @Min(0)
    @Max(1)
    private Integer enabled;
}
