package com.hify.knowledge.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeErrorCode;
import com.hify.knowledge.dto.ChunkResp;
import com.hify.knowledge.dto.DocumentResp;
import com.hify.knowledge.entity.KnowledgeDocument;
import com.hify.knowledge.mapper.KnowledgeDocumentMapper;
import com.hify.knowledge.service.DocumentService;
import com.hify.knowledge.service.KnowledgeBaseService;
import com.hify.knowledge.vector.PgVectorStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识库文档服务：上传（txt / md 纯文本）→ 触发异步管线，状态轮询见列表接口。
 */
@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Set<String> SUPPORTED_TYPES = Set.of("txt", "md");

    private final KnowledgeDocumentMapper documentMapper;

    private final KnowledgeBaseService knowledgeBaseService;

    private final DocumentPipeline pipeline;

    private final PgVectorStore vectorStore;

    private final long maxFileSize;

    public DocumentServiceImpl(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeBaseService knowledgeBaseService,
            DocumentPipeline pipeline,
            PgVectorStore vectorStore,
            @Value("${hify.knowledge.max-file-size:5242880}") long maxFileSize) {
        this.documentMapper = documentMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.pipeline = pipeline;
        this.vectorStore = vectorStore;
        this.maxFileSize = maxFileSize;
    }

    @Override
    public DocumentResp upload(Long knowledgeBaseId, MultipartFile file) {
        knowledgeBaseService.getEntity(knowledgeBaseId);

        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String fileType = extensionOf(fileName);
        if (!SUPPORTED_TYPES.contains(fileType)) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_FILE_TYPE_NOT_SUPPORTED,
                    "不支持的文件类型：" + fileName + "（当前仅 txt / md）");
        }
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_FILE_INVALID, "文件内容为空");
        }
        if (file.getSize() > maxFileSize) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_FILE_INVALID,
                    "文件超过大小上限：" + file.getSize() + " > " + maxFileSize + " 字节");
        }

        // 先入库拿 id（文件按 id 命名落盘，避免重名 / 路径穿越），再写盘，失败直接标 failed
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseId(knowledgeBaseId);
        doc.setFileName(fileName);
        doc.setFileType(fileType);
        doc.setFileSize(file.getSize());
        doc.setStatus(KnowledgeDocument.STATUS_PENDING);
        doc.setErrorMsg("");
        documentMapper.insert(doc);

        try {
            Files.createDirectories(pipeline.filePath(doc).getParent());
            file.transferTo(pipeline.filePath(doc));
        } catch (IOException e) {
            log.error("文档落盘失败 documentId={}", doc.getId(), e);
            KnowledgeDocument failed = new KnowledgeDocument();
            failed.setId(doc.getId());
            failed.setStatus(KnowledgeDocument.STATUS_FAILED);
            failed.setErrorMsg("文件落盘失败：" + e.getMessage());
            documentMapper.updateById(failed);
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_FILE_INVALID, "文件保存失败：" + e.getMessage());
        }

        pipeline.submit(doc.getId());
        return DocumentResp.of(documentMapper.selectById(doc.getId()), 0);
    }

    @Override
    public List<DocumentResp> list(Long knowledgeBaseId) {
        knowledgeBaseService.getEntity(knowledgeBaseId);
        List<KnowledgeDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(KnowledgeDocument::getId));
        if (docs.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> chunkCounts = vectorStore.countByDocuments(
                docs.stream().map(KnowledgeDocument::getId).toList());
        return docs.stream()
                .map(doc -> DocumentResp.of(doc, chunkCounts.getOrDefault(doc.getId(), 0)))
                .toList();
    }

    @Override
    public void delete(Long id) {
        KnowledgeDocument doc = requireDocument(id);
        documentMapper.deleteById(id);            // 逻辑删除（元数据可追溯）
        vectorStore.deleteByDocument(id);         // 向量物理删除
        try {
            Files.deleteIfExists(pipeline.filePath(doc));
        } catch (IOException e) {
            log.warn("原始文件删除失败（不影响功能） documentId={} 原因={}", id, e.getMessage());
        }
    }

    @Override
    public void retry(Long id) {
        KnowledgeDocument doc = requireDocument(id);
        if (!KnowledgeDocument.STATUS_FAILED.equals(doc.getStatus())) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_DOCUMENT_STATUS_INVALID,
                    "仅失败状态的文档可重试，当前：" + doc.getStatus());
        }
        pipeline.submit(id);
    }

    @Override
    public List<ChunkResp> listChunks(Long documentId, Integer offset, Integer limit) {
        requireDocument(documentId);
        int off = offset == null ? 0 : Math.max(offset, 0);
        int size = limit == null ? 10 : Math.min(Math.max(limit, 1), 50);
        List<PgVectorStore.ChunkHit> hits = vectorStore.listChunks(documentId, off, size);
        return hits.stream().map(ChunkResp::of).toList();
    }

    // ---------- 内部 ----------

    private KnowledgeDocument requireDocument(Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND, "文档不存在，id=" + id);
        }
        return doc;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
