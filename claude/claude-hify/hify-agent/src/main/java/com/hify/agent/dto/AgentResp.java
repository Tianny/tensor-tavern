package com.hify.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import com.hify.agent.entity.Agent;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;

import lombok.Data;

/**
 * Agent 响应。附带绑定的模型/提供商信息（由 Service 联表查好传入），
 * 模型配置已被删除时 modelName / providerName 为空串，前端标红提示。
 */
@Data
public class AgentResp {

    private Long id;

    private String name;

    private String description;

    private Long modelConfigId;

    /** 绑定模型的 model_name；配置已删时为空串 */
    private String modelName;

    private Long providerId;

    private String providerName;

    private String systemPrompt;

    private BigDecimal temperature;

    private Integer maxTokens;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * @param models  model_config.id → 实体（可为空 map，表示无绑定信息）
     * @param providers provider.id → 实体
     */
    public static AgentResp of(Agent agent, Map<Long, ModelConfig> models, Map<Long, Provider> providers) {
        AgentResp resp = new AgentResp();
        resp.setId(agent.getId());
        resp.setName(agent.getName());
        resp.setDescription(agent.getDescription());
        resp.setModelConfigId(agent.getModelConfigId());
        ModelConfig model = models.get(agent.getModelConfigId());
        if (model != null) {
            resp.setModelName(model.getModelName());
            resp.setProviderId(model.getProviderId());
            Provider provider = providers.get(model.getProviderId());
            resp.setProviderName(provider == null ? "" : provider.getName());
        } else {
            resp.setModelName("");
            resp.setProviderName("");
        }
        resp.setSystemPrompt(agent.getSystemPrompt());
        resp.setTemperature(agent.getTemperature());
        resp.setMaxTokens(agent.getMaxTokens());
        resp.setEnabled(agent.getEnabled());
        resp.setCreatedAt(agent.getCreatedAt());
        resp.setUpdatedAt(agent.getUpdatedAt());
        return resp;
    }
}
