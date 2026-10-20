package com.hify.provider.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.dto.PageResult;
import com.hify.common.exception.BizException;
import com.hify.common.http.LlmApiException;
import com.hify.common.util.PageHelper;
import com.hify.provider.constant.ProviderErrorCode;
import com.hify.provider.dto.ModelConfigCreateReq;
import com.hify.provider.dto.ModelConfigResp;
import com.hify.provider.dto.ModelConfigUpdateReq;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.mapper.ModelConfigMapper;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderHttpService;
import com.hify.provider.service.ProviderService;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型配置服务实现。
 */
@Slf4j
@Service
public class ModelConfigServiceImpl extends ServiceImpl<ModelConfigMapper, ModelConfig> implements ModelConfigService {

    private static final String MODEL_TYPE_CHAT = "chat";

    private static final String MODEL_TYPE_EMBEDDING = "embedding";

    private final ProviderService providerService;

    private final ProviderHttpService httpService;

    private final ObjectMapper objectMapper;

    public ModelConfigServiceImpl(ProviderService providerService, ProviderHttpService httpService,
            ObjectMapper objectMapper) {
        this.providerService = providerService;
        this.httpService = httpService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<ModelConfigResp> page(Integer page, Integer pageSize, Long providerId, String modelType) {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<>();
        if (providerId != null) {
            wrapper.eq(ModelConfig::getProviderId, providerId);
        }
        if (StringUtils.hasText(modelType)) {
            wrapper.eq(ModelConfig::getModelType, modelType);
        }
        wrapper.orderByDesc(ModelConfig::getId);
        Page<ModelConfig> result = page(PageHelper.toPage(page, pageSize), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(ModelConfigResp::of).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    @Override
    public ModelConfigResp create(ModelConfigCreateReq req) {
        providerService.getEntity(req.getProviderId());   // 校验 provider 存在，不存在抛 2001
        validateModelType(req.getModelType());
        checkNameDuplicate(req.getProviderId(), req.getModelName(), null);

        ModelConfig model = new ModelConfig();
        model.setProviderId(req.getProviderId());
        model.setModelName(req.getModelName().trim());
        model.setDisplayName(StringUtils.hasText(req.getDisplayName())
                ? req.getDisplayName().trim() : req.getModelName().trim());
        model.setModelType(req.getModelType());
        model.setMaxContextTokens(req.getMaxContextTokens() == null ? 0 : req.getMaxContextTokens());
        model.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        save(model);
        return ModelConfigResp.of(model);
    }

    @Override
    public ModelConfigResp update(Long id, ModelConfigUpdateReq req) {
        ModelConfig exists = getById(id);
        if (exists == null) {
            throw new BizException(ProviderErrorCode.MODEL_CONFIG_NOT_FOUND, "模型配置不存在，id=" + id);
        }
        validateModelType(req.getModelType());
        checkNameDuplicate(exists.getProviderId(), req.getModelName(), id);

        ModelConfig model = new ModelConfig();
        model.setId(id);
        model.setModelName(req.getModelName().trim());
        model.setDisplayName(StringUtils.hasText(req.getDisplayName())
                ? req.getDisplayName().trim() : req.getModelName().trim());
        model.setModelType(req.getModelType());
        model.setMaxContextTokens(req.getMaxContextTokens() == null ? exists.getMaxContextTokens() : req.getMaxContextTokens());
        model.setEnabled(req.getEnabled() == null ? exists.getEnabled() : req.getEnabled());
        updateById(model);
        return ModelConfigResp.of(getById(id));
    }

    @Override
    public void delete(Long id) {
        if (getById(id) == null) {
            throw new BizException(ProviderErrorCode.MODEL_CONFIG_NOT_FOUND, "模型配置不存在，id=" + id);
        }
        removeById(id);   // 逻辑删除
    }

    @Override
    public List<String> fetchRemoteModels(Long providerId) {
        Provider provider = providerService.getEntity(providerId);
        String body;
        try {
            body = httpService.fetchModelsBody(provider);
        } catch (LlmApiException e) {
            String reason = ProviderHttpService.describeFailure(e);
            log.warn("拉取模型列表失败 providerId={} 原因={}", providerId, e.getMessage());
            throw new BizException(ProviderErrorCode.PROVIDER_REMOTE_MODELS_FAILED,
                    "拉取模型列表失败：" + reason);
        }
        return parseModelNames(body);
    }

    // ---------- 内部 ----------

    private void validateModelType(String modelType) {
        if (!MODEL_TYPE_CHAT.equals(modelType) && !MODEL_TYPE_EMBEDDING.equals(modelType)) {
            throw new BizException(ProviderErrorCode.MODEL_TYPE_INVALID,
                    "模型类型只能是 chat 或 embedding，当前：" + modelType);
        }
    }

    private void checkNameDuplicate(Long providerId, String modelName, Long excludeId) {
        LambdaQueryWrapper<ModelConfig> wrapper = new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getProviderId, providerId)
                .eq(ModelConfig::getModelName, modelName);
        if (excludeId != null) {
            wrapper.ne(ModelConfig::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BizException(ProviderErrorCode.MODEL_NAME_DUPLICATED,
                    "同一提供商下模型名已存在：" + modelName);
        }
    }

    /**
     * 各家响应字段名不同：openai/claude 是 data[].id，
     * gemini 是 models[].name（带 models/ 前缀），ollama 是 models[].name。
     */
    private List<String> parseModelNames(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode list = root.get("data");
            if (list == null || !list.isArray()) {
                list = root.get("models");
            }
            if (list == null || !list.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode node : list) {
                String name = node.hasNonNull("id") ? node.get("id").asText() : null;
                if (name == null && node.hasNonNull("name")) {
                    name = node.get("name").asText();
                }
                if (name != null && !name.isBlank()) {
                    if (name.startsWith("models/")) {
                        name = name.substring("models/".length());   // gemini 前缀
                    }
                    names.add(name);
                }
            }
            names.sort(String::compareTo);   // 远端顺序不稳定，排序保证两次拉取可比
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }
}
