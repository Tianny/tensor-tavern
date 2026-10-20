package com.hify.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hify.agent.dto.AgentCreateReq;
import com.hify.agent.dto.AgentResp;
import com.hify.agent.dto.AgentUpdateReq;
import com.hify.agent.entity.Agent;
import com.hify.common.dto.PageResult;

/**
 * Agent 服务接口（跨模块调用走本接口，不引实现类）。
 */
public interface AgentService extends IService<Agent> {

    /** 分页查询，name 模糊 */
    PageResult<AgentResp> page(Integer page, Integer pageSize, String name);

    /** 按 id 取实体（带缓存，供后续 chat 模块读取 Agent 配置） */
    Agent getEntity(Long id);

    AgentResp getDetail(Long id);

    AgentResp create(AgentCreateReq req);

    AgentResp update(Long id, AgentUpdateReq req);

    /** 逻辑删除 */
    void delete(Long id);
}
