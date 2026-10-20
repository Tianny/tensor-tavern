package com.hify.agent.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hify.agent.constant.AgentErrorCode;
import com.hify.agent.dto.AgentCreateReq;
import com.hify.agent.dto.AgentResp;
import com.hify.agent.dto.AgentUpdateReq;
import com.hify.agent.entity.Agent;
import com.hify.agent.mapper.AgentMapper;
import com.hify.agent.service.AgentService;
import com.hify.common.config.CacheConfig;
import com.hify.common.dto.PageResult;
import com.hify.common.exception.BizException;
import com.hify.common.util.PageHelper;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderService;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

/**
 * Agent 服务实现。模型绑定校验与列表联表经 hify-provider 的 Service 接口完成
 * （模块间不引对方的 Mapper / Entity 实现层之外的内部方法）。
 */
@Service
@RequiredArgsConstructor
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Agent> implements AgentService {

    private static final String MODEL_TYPE_CHAT = "chat";

    private static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");

    private final ModelConfigService modelConfigService;

    private final ProviderService providerService;

    @Override
    public PageResult<AgentResp> page(Integer page, Integer pageSize, String name) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(Agent::getName, name);
        }
        wrapper.orderByDesc(Agent::getId);
        Page<Agent> result = page(PageHelper.toPage(page, pageSize), wrapper);
        List<AgentResp> records = joinModelInfo(result.getRecords());
        return PageResult.of(
                records, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.AGENT_CACHE, key = "#id")
    public Agent getEntity(Long id) {
        Agent agent = getById(id);
        if (agent == null) {
            throw new BizException(AgentErrorCode.AGENT_NOT_FOUND, "Agent 不存在，id=" + id);
        }
        return agent;
    }

    @Override
    public AgentResp getDetail(Long id) {
        return joinModelInfo(List.of(getEntity(id))).get(0);
    }

    @Override
    public AgentResp create(AgentCreateReq req) {
        validateModelConfig(req.getModelConfigId());
        checkNameDuplicate(req.getName(), null);

        Agent agent = new Agent();
        agent.setName(req.getName().trim());
        agent.setDescription(trimToEmpty(req.getDescription()));
        agent.setModelConfigId(req.getModelConfigId());
        agent.setSystemPrompt(trimToEmpty(req.getSystemPrompt()));
        agent.setTemperature(req.getTemperature() == null ? DEFAULT_TEMPERATURE : req.getTemperature());
        agent.setMaxTokens(req.getMaxTokens() == null ? 0 : req.getMaxTokens());
        agent.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        save(agent);
        return getDetail(agent.getId());
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.AGENT_CACHE, key = "#id")
    public AgentResp update(Long id, AgentUpdateReq req) {
        Agent exists = getEntity(id);
        validateModelConfig(req.getModelConfigId());
        checkNameDuplicate(req.getName(), id);

        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(req.getName().trim());
        agent.setDescription(trimToEmpty(req.getDescription()));
        agent.setModelConfigId(req.getModelConfigId());
        // null=保持原值（清空 system_prompt 传空串）
        agent.setSystemPrompt(req.getSystemPrompt() == null ? exists.getSystemPrompt() : req.getSystemPrompt().trim());
        agent.setTemperature(req.getTemperature() == null ? exists.getTemperature() : req.getTemperature());
        agent.setMaxTokens(req.getMaxTokens() == null ? exists.getMaxTokens() : req.getMaxTokens());
        agent.setEnabled(req.getEnabled() == null ? exists.getEnabled() : req.getEnabled());
        updateById(agent);
        // 联表信息取自库中最新值（getById 不走缓存，避免读到旧实体）
        return joinModelInfo(List.of(getById(id))).get(0);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.AGENT_CACHE, key = "#id")
    public void delete(Long id) {
        if (getById(id) == null) {
            throw new BizException(AgentErrorCode.AGENT_NOT_FOUND, "Agent 不存在，id=" + id);
        }
        removeById(id);   // 逻辑删除
    }

    // ---------- 内部 ----------

    /** 绑定的模型必须存在、启用且为 chat 类型（防止绑到 embedding 或已下线的模型） */
    private void validateModelConfig(Long modelConfigId) {
        ModelConfig model = modelConfigService.getById(modelConfigId);
        if (model == null || model.getEnabled() != 1 || !MODEL_TYPE_CHAT.equals(model.getModelType())) {
            throw new BizException(AgentErrorCode.AGENT_MODEL_CONFIG_INVALID,
                    "模型配置不可用（不存在、已删除、停用或非 chat 类型），id=" + modelConfigId);
        }
    }

    private void checkNameDuplicate(String name, Long excludeId) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>()
                .eq(Agent::getName, name);
        if (excludeId != null) {
            wrapper.ne(Agent::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BizException(AgentErrorCode.AGENT_NAME_DUPLICATED, "名称已存在：" + name);
        }
    }

    /** 列表/详情联表：模型配置和提供商各批量查一次，模型配置已删时名称留空串 */
    private List<AgentResp> joinModelInfo(List<Agent> agents) {
        List<Long> modelIds = agents.stream()
                .map(Agent::getModelConfigId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ModelConfig> models = modelIds.isEmpty()
                ? Map.of()
                : modelConfigService.listByIds(modelIds).stream()
                        .collect(Collectors.toMap(ModelConfig::getId, Function.identity()));
        List<Long> providerIds = models.values().stream()
                .map(ModelConfig::getProviderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Provider> providers = providerIds.isEmpty()
                ? Map.of()
                : providerService.listByIds(providerIds).stream()
                        .collect(Collectors.toMap(Provider::getId, Function.identity()));
        return agents.stream()
                .map(agent -> AgentResp.of(agent, models, providers))
                .toList();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
