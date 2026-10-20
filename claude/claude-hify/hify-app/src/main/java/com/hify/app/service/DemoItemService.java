package com.hify.app.service;

import com.hify.app.dto.DemoItemCreateReq;
import com.hify.app.dto.DemoItemResp;
import com.hify.app.dto.DemoItemUpdateReq;

import com.hify.common.dto.PageResult;

/**
 * CRUD 流程演示 Service。
 */
public interface DemoItemService {

    /** 分页列表，name 为可选模糊过滤条件 */
    PageResult<DemoItemResp> list(Integer page, Integer pageSize, String name);

    /** 详情，不存在抛 NOT_FOUND */
    DemoItemResp getById(Long id);

    /** 创建，名称重复抛 PARAM_ERROR；返回新记录 id */
    Long create(DemoItemCreateReq req);

    /** 全量更新，不存在抛 NOT_FOUND，名称与他人重复抛 PARAM_ERROR */
    void update(Long id, DemoItemUpdateReq req);

    /** 逻辑删除，不存在抛 NOT_FOUND */
    void delete(Long id);
}
