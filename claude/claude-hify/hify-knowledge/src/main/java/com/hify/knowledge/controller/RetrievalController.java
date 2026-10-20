package com.hify.knowledge.controller;

import java.util.List;

import com.hify.common.dto.Result;
import com.hify.knowledge.dto.RetrievalHit;
import com.hify.knowledge.dto.RetrievalReq;
import com.hify.knowledge.service.KnowledgeRetrievalService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 检索测试接口（知识库详情页的"命中调试"面板）。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class RetrievalController {

    private final KnowledgeRetrievalService retrievalService;

    @PostMapping("/{id}/search")
    public Result<List<RetrievalHit>> search(@PathVariable Long id, @Valid @RequestBody RetrievalReq req) {
        return Result.ok(retrievalService.search(id, req.getQuery(), req.getTopK()));
    }
}
