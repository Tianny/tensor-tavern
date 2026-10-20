package com.hify.knowledge.service;

import java.util.List;

import com.hify.knowledge.dto.RetrievalContext;
import com.hify.knowledge.dto.RetrievalHit;

/**
 * RAG 检索服务接口（跨模块：hify-chat 对话时调用）。
 */
public interface KnowledgeRetrievalService {

    /**
     * 按 Agent 绑定的知识库检索（多库合并，按相似度排序取 topK），
     * 并拼装成可直接追加到 system prompt 的参考资料片段。
     *
     * <p>embed 用知识库各自绑定的模型（不同库可能不同模型，分组检索后合并）；
     * 绑定库的 embedding 模型不可用时跳过该库；全部不可用/无命中返回空上下文。
     */
    RetrievalContext retrieveForAgent(Long agentId, String query);

    /** 检索测试（指定单个知识库） */
    List<RetrievalHit> search(Long knowledgeBaseId, String query, Integer topK);
}
