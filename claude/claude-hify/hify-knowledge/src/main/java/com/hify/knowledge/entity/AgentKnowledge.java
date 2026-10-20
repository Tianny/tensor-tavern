package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 与知识库绑定（M:N）。表由 knowledge 模块持有，agent_id 只作列值，
 * 不反向依赖 hify-agent（绑定管理的接口在前端按 agent 资源路径调用）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_knowledge")
public class AgentKnowledge extends BaseEntity {

    /** Agent agent.id */
    private Long agentId;

    /** 知识库 knowledge_base.id */
    private Long knowledgeBaseId;
}
