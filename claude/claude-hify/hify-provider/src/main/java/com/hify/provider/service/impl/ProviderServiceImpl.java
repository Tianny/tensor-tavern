package com.hify.provider.service.impl;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.config.CacheConfig;
import com.hify.common.dto.PageResult;
import com.hify.common.exception.BizException;
import com.hify.common.http.LlmApiException;
import com.hify.common.util.PageHelper;
import com.hify.provider.constant.ProviderErrorCode;
import com.hify.provider.constant.ProviderType;
import com.hify.provider.dto.ProviderCreateReq;
import com.hify.provider.dto.ProviderResp;
import com.hify.provider.dto.ProviderUpdateReq;
import com.hify.provider.dto.TestConnectionResp;
import com.hify.provider.entity.Provider;
import com.hify.provider.mapper.ProviderMapper;
import com.hify.provider.service.ProviderHttpService;
import com.hify.provider.service.ProviderService;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Provider 服务实现。出站 HTTP（探测 / 拉取模型列表）委托 {@link ProviderHttpService}。
 */
@Slf4j
@Service
public class ProviderServiceImpl extends ServiceImpl<ProviderMapper, Provider> implements ProviderService {

    private final ProviderHttpService httpService;

    private final ObjectMapper objectMapper;

    public ProviderServiceImpl(ProviderHttpService httpService, ObjectMapper objectMapper) {
        this.httpService = httpService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<ProviderResp> page(Integer page, Integer pageSize, String name, String type) {
        LambdaQueryWrapper<Provider> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(Provider::getName, name);
        }
        if (StringUtils.hasText(type)) {
            wrapper.eq(Provider::getType, type);
        }
        wrapper.orderByDesc(Provider::getId);
        Page<Provider> result = page(PageHelper.toPage(page, pageSize), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(ProviderResp::of).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.PROVIDER_CACHE, key = "#id")
    public Provider getEntity(Long id) {
        Provider provider = getById(id);
        if (provider == null) {
            throw new BizException(ProviderErrorCode.PROVIDER_NOT_FOUND, "Provider 不存在，id=" + id);
        }
        return provider;
    }

    @Override
    public ProviderResp getDetail(Long id) {
        return ProviderResp.of(getEntity(id));
    }

    @Override
    public ProviderResp create(ProviderCreateReq req) {
        ProviderType type = parseType(req.getType());
        String baseUrl = httpService.resolveBaseUrl(type, req.getBaseUrl());
        validateApiKey(type, req.getApiKey());
        checkNameDuplicate(req.getName(), null);

        Provider provider = new Provider();
        provider.setName(req.getName().trim());
        provider.setType(type.getCode());
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(trimToEmpty(req.getApiKey()));
        provider.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        provider.setRemark(trimToEmpty(req.getRemark()));
        save(provider);
        return ProviderResp.of(provider);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.PROVIDER_CACHE, key = "#id")
    public ProviderResp update(Long id, ProviderUpdateReq req) {
        Provider exists = getEntity(id);
        ProviderType type = parseType(req.getType());
        String baseUrl = httpService.resolveBaseUrl(type, req.getBaseUrl());
        // api_key 留空 = 保持原值（前端编辑不回传明文，只回掩码）
        String apiKey = StringUtils.hasText(req.getApiKey()) ? req.getApiKey().trim() : exists.getApiKey();
        validateApiKey(type, apiKey);
        checkNameDuplicate(req.getName(), id);

        Provider provider = new Provider();
        provider.setId(id);
        provider.setName(req.getName().trim());
        provider.setType(type.getCode());
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setEnabled(req.getEnabled() == null ? exists.getEnabled() : req.getEnabled());
        provider.setRemark(trimToEmpty(req.getRemark()));
        updateById(provider);
        return ProviderResp.of(getById(id));
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.PROVIDER_CACHE, key = "#id")
    public void delete(Long id) {
        if (getById(id) == null) {
            throw new BizException(ProviderErrorCode.PROVIDER_NOT_FOUND, "Provider 不存在，id=" + id);
        }
        removeById(id);   // 逻辑删除
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.PROVIDER_CACHE, key = "#id")
    public TestConnectionResp testConnection(Long id) {
        Provider provider = getEntity(id);
        long start = System.currentTimeMillis();
        TestConnectionResp resp = new TestConnectionResp();
        try {
            String body = httpService.fetchModelsBody(provider);
            resp.setOk(true);
            resp.setModelCount(countModels(body));
            resp.setMessage(resp.getModelCount() != null && resp.getModelCount() > 0
                    ? "连接成功，发现 " + resp.getModelCount() + " 个模型"
                    : "连接成功");
        } catch (LlmApiException e) {
            resp.setOk(false);
            resp.setMessage(ProviderHttpService.describeFailure(e));
            log.warn("Provider 连通性测试失败 id={} 原因={}", id, e.getMessage());
        }
        resp.setLatencyMs((int) (System.currentTimeMillis() - start));
        resp.setTestedAt(LocalDateTime.now());
        persistTestResult(id, resp);
        return resp;
    }

    // ---------- 内部 ----------

    private ProviderType parseType(String code) {
        ProviderType type = ProviderType.of(code);
        if (type == null) {
            throw new BizException(ProviderErrorCode.PROVIDER_TYPE_INVALID,
                    "不支持的类型：" + code + "，可选：openai / openai_compatible / claude / gemini / ollama");
        }
        return type;
    }

    private void validateApiKey(ProviderType type, String apiKey) {
        if (type.isApiKeyRequired() && !StringUtils.hasText(apiKey)) {
            throw new BizException(ProviderErrorCode.PROVIDER_API_KEY_REQUIRED,
                    "类型 " + type.getCode() + " 必须配置 API Key");
        }
    }

    private void checkNameDuplicate(String name, Long excludeId) {
        LambdaQueryWrapper<Provider> wrapper = new LambdaQueryWrapper<Provider>()
                .eq(Provider::getName, name);
        if (excludeId != null) {
            wrapper.ne(Provider::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BizException(ProviderErrorCode.PROVIDER_NAME_DUPLICATED, "名称已存在：" + name);
        }
    }

    /** 各家 models 响应的字段名不同：openai/claude 是 data，gemini/ollama 是 models */
    private Integer countModels(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            for (String field : new String[] {"data", "models"}) {
                JsonNode node = root.get(field);
                if (node != null && node.isArray()) {
                    return node.size();
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;   // 响应非 JSON 也算连通（200 已返回），只是解析不出模型数
        }
    }

    private void persistTestResult(Long id, TestConnectionResp resp) {
        Provider update = new Provider();
        update.setId(id);
        update.setLastTestAt(resp.getTestedAt());
        update.setLastTestOk(resp.isOk() ? 1 : 0);
        update.setLastTestMessage(resp.getMessage());
        updateById(update);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
