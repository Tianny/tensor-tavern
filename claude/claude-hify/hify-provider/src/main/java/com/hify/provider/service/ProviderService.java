package com.hify.provider.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hify.common.dto.PageResult;
import com.hify.provider.dto.ProviderCreateReq;
import com.hify.provider.dto.ProviderResp;
import com.hify.provider.dto.ProviderUpdateReq;
import com.hify.provider.dto.TestConnectionResp;
import com.hify.provider.entity.Provider;

/**
 * Provider 服务接口（跨模块调用走本接口，不引实现类）。
 */
public interface ProviderService extends IService<Provider> {

    /** 分页查询，name 模糊、type 精确 */
    PageResult<ProviderResp> page(Integer page, Integer pageSize, String name, String type);

    /** 按 id 取实体（带缓存，供连通性测试 / 后续 chat 模块复用） */
    Provider getEntity(Long id);

    ProviderResp getDetail(Long id);

    ProviderResp create(ProviderCreateReq req);

    ProviderResp update(Long id, ProviderUpdateReq req);

    /** 逻辑删除 */
    void delete(Long id);

    /** 连通性测试：真实探测提供商 models 接口，结果落库（last_test_*） */
    TestConnectionResp testConnection(Long id);
}
