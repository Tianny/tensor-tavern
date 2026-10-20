package com.hify.knowledge.controller;

import java.util.List;

import com.hify.common.dto.Result;
import com.hify.knowledge.dto.KnowledgeBaseCreateReq;
import com.hify.knowledge.dto.KnowledgeBaseResp;
import com.hify.knowledge.dto.KnowledgeBaseUpdateReq;
import com.hify.knowledge.service.KnowledgeBaseService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 知识库管理接口。数量级小（团队内部），列表不分页。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    public Result<List<KnowledgeBaseResp>> list() {
        return Result.ok(knowledgeBaseService.list());
    }

    @PostMapping
    public Result<KnowledgeBaseResp> create(@Valid @RequestBody KnowledgeBaseCreateReq req) {
        return Result.ok(knowledgeBaseService.create(req));
    }

    @PutMapping("/{id}")
    public Result<KnowledgeBaseResp> update(@PathVariable Long id, @Valid @RequestBody KnowledgeBaseUpdateReq req) {
        return Result.ok(knowledgeBaseService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.ok();
    }
}
