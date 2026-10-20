package com.hify.knowledge.service;

import java.util.List;

import com.hify.knowledge.dto.AgentKnowledgeUpdateReq;
import com.hify.knowledge.dto.KnowledgeBaseCreateReq;
import com.hify.knowledge.dto.KnowledgeBaseResp;
import com.hify.knowledge.dto.KnowledgeBaseUpdateReq;
import com.hify.knowledge.entity.KnowledgeBase;

/**
 * 知识库管理服务接口（本模块内使用，Agent 绑定接口给前端用）。
 */
public interface KnowledgeBaseService {

    List<KnowledgeBaseResp> list();

    KnowledgeBaseResp create(KnowledgeBaseCreateReq req);

    KnowledgeBaseResp update(Long id, KnowledgeBaseUpdateReq req);

    void delete(Long id);

    /** 按 id 取实体，不存在抛 7001（模块内与检索共用） */
    KnowledgeBase getEntity(Long id);

    /** Agent 绑定的知识库 id 列表 */
    List<Long> listBoundKnowledgeBaseIds(Long agentId);

    /** 全量覆盖 Agent 的知识库绑定 */
    void updateAgentBindings(Long agentId, AgentKnowledgeUpdateReq req);
}
