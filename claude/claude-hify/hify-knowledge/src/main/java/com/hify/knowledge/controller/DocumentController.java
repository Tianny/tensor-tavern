package com.hify.knowledge.controller;

import java.util.List;

import com.hify.common.dto.Result;
import com.hify.knowledge.dto.ChunkResp;
import com.hify.knowledge.dto.DocumentResp;
import com.hify.knowledge.service.DocumentService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库文档接口。上传后异步向量化，前端轮询列表看状态。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /** multipart 上传（txt / md 纯文本），返回 pending 态的文档记录 */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public Result<DocumentResp> upload(@PathVariable Long knowledgeBaseId,
            @RequestPart("file") MultipartFile file) {
        return Result.ok(documentService.upload(knowledgeBaseId, file));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public Result<List<DocumentResp>> list(@PathVariable Long knowledgeBaseId) {
        return Result.ok(documentService.list(knowledgeBaseId));
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.ok();
    }

    @PostMapping("/documents/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        documentService.retry(id);
        return Result.ok();
    }

    /** 分块预览（token 估算数正序分页） */
    @GetMapping("/documents/{id}/chunks")
    public Result<List<ChunkResp>> chunks(@PathVariable Long id,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return Result.ok(documentService.listChunks(id, offset, limit));
    }
}
