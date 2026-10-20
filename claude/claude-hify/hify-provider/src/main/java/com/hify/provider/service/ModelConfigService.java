package com.hify.provider.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hify.common.dto.PageResult;
import com.hify.provider.dto.ModelConfigCreateReq;
import com.hify.provider.dto.ModelConfigResp;
import com.hify.provider.dto.ModelConfigUpdateReq;
import com.hify.provider.entity.ModelConfig;

/**
 * 模型配置服务接口（跨模块调用走本接口）。
 */
public interface ModelConfigService extends IService<ModelConfig> {

    /** 分页查询：providerId 精确、modelType 精确 */
    PageResult<ModelConfigResp> page(Integer page, Integer pageSize, Long providerId, String modelType);

    ModelConfigResp create(ModelConfigCreateReq req);

    ModelConfigResp update(Long id, ModelConfigUpdateReq req);

    /** 逻辑删除 */
    void delete(Long id);

    /**
     * 从提供商远端拉取可用模型名列表（各家 GET models 端点，仅返回名字，
     * 元数据少，入库需人工补充 maxContextTokens 等信息）。
     */
    List<String> fetchRemoteModels(Long providerId);
}
