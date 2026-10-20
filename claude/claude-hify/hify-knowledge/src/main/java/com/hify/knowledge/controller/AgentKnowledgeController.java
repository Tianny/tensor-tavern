package com.hify.knowledge.controller;

import java.util.List;

import com.hify.common.dto.Result;
import com.hify.knowledge.dto.AgentKnowledgeUpdateReq;
import com.hify.knowledge.dto.KnowledgeBaseResp;
import com.hify.knowledge.service.KnowledgeBaseService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Agent 与知识库绑定接口。挂在 agent 资源路径下（绑定表由本模块持有，
 * 不反向依赖 hify-agent——agent_id 只是列值）。
 */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/knowledge-bases")
@RequiredArgsConstructor
public class AgentKnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    /** Agent 当前绑定的知识库（含模型信息，供表单回显） */
    @GetMapping
    public Result<List<KnowledgeBaseResp>> listBound(@PathVariable Long agentId) {
        List<Long> kbIds = knowledgeBaseService.listBoundKnowledgeBaseIds(agentId);
        return Result.ok(knowledgeBaseService.list().stream()
                .filter(kb -> kbIds.contains(kb.getId()))
                .toList());
    }

    /** 全量覆盖绑定（空数组 = 全部解绑） */
    @PutMapping
    public Result<Void> updateBindings(@PathVariable Long agentId, @Valid @RequestBody AgentKnowledgeUpdateReq req) {
        knowledgeBaseService.updateAgentBindings(agentId, req);
        return Result.ok();
    }
}
