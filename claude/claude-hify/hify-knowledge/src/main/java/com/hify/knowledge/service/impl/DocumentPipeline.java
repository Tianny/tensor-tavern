package com.hify.knowledge.service.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.hify.common.exception.BizException;
import com.hify.common.http.LlmApiException;
import com.hify.knowledge.constant.KnowledgeErrorCode;
import com.hify.knowledge.entity.KnowledgeBase;
import com.hify.knowledge.entity.KnowledgeDocument;
import com.hify.knowledge.mapper.KnowledgeBaseMapper;
import com.hify.knowledge.mapper.KnowledgeDocumentMapper;
import com.hify.knowledge.service.EmbeddingClient;
import com.hify.knowledge.service.TextChunker;
import com.hify.knowledge.vector.PgVectorStore;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderService;
import com.hify.provider.service.ProviderHttpService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 文档向量化管线（异步，asyncExecutor）：
 * 读取原文 → 分块 → 调 embedding（分批 + 熔断重试）→ 写 pgvector → 状态机流转。
 * 失败从头重跑（replaceChunks 先清旧向量），不做断点续跑——文档量级小，全量重刷最简单。
 */
@Slf4j
@Service
public class DocumentPipeline {

    private final KnowledgeDocumentMapper documentMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final ModelConfigService modelConfigService;

    private final ProviderService providerService;

    private final TextChunker textChunker;

    private final EmbeddingClient embeddingClient;

    private final PgVectorStore vectorStore;

    private final ThreadPoolTaskExecutor asyncExecutor;

    private final Path uploadDir;

    public DocumentPipeline(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            ModelConfigService modelConfigService,
            ProviderService providerService,
            TextChunker textChunker,
            EmbeddingClient embeddingClient,
            PgVectorStore vectorStore,
            @Qualifier("asyncExecutor") ThreadPoolTaskExecutor asyncExecutor,
            @Value("${hify.knowledge.upload-dir:./data/knowledge}") String uploadDir) {
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.modelConfigService = modelConfigService;
        this.providerService = providerService;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.asyncExecutor = asyncExecutor;
        this.uploadDir = Path.of(uploadDir);
    }

    /** 提交异步处理（上传 / 重试入口调用） */
    public void submit(Long documentId) {
        asyncExecutor.execute(() -> process(documentId));
    }

    private void process(Long documentId) {
        try {
            doProcess(documentId);
        } catch (Exception e) {
            log.error("文档向量化管线异常 documentId={}", documentId, e);
            markFailed(documentId, e.getMessage());
        }
    }

    private void doProcess(Long documentId) throws IOException {
        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("文档不存在，管线跳过 documentId={}", documentId);
            return;
        }
        updateStatus(documentId, KnowledgeDocument.STATUS_PROCESSING, "");

        // 1. 读原文
        String text;
        try {
            text = Files.readString(filePath(doc), StandardCharsets.UTF_8);
        } catch (IOException e) {
            markFailed(documentId, "原始文件读取失败：" + e.getMessage());
            return;
        }
        if (text.isBlank()) {
            markFailed(documentId, "文档内容为空");
            return;
        }

        // 2. 分块
        List<TextChunker.Chunk> chunks = textChunker.chunk(text);
        if (chunks.isEmpty()) {
            markFailed(documentId, "分块结果为空");
            return;
        }

        // 3. 向量化（配置来自知识库绑定的 embedding 模型）
        KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKnowledgeBaseId());
        ModelConfig model = modelConfigService.getById(kb.getEmbeddingModelId());
        Provider provider = providerService.getEntity(model.getProviderId());
        List<String> contents = chunks.stream().map(TextChunker.Chunk::content).toList();
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(provider, model, contents);
        } catch (LlmApiException e) {
            markFailed(documentId, ProviderHttpService.describeFailure(e));
            return;
        } catch (BizException e) {
            // 熔断开启 / 维度校验失败等
            markFailed(documentId, e.getMessage());
            return;
        }

        // 4. 写 pgvector（先清旧向量，幂等）
        List<PgVectorStore.ChunkRow> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            rows.add(new PgVectorStore.ChunkRow(i, chunks.get(i).content(), chunks.get(i).tokenCount(), vectors.get(i)));
        }
        vectorStore.replaceChunks(kb.getId(), documentId, rows);

        updateStatus(documentId, KnowledgeDocument.STATUS_COMPLETED, "");
        log.info("文档向量化完成 documentId={} 文件={} 分块数={}", documentId, doc.getFileName(), rows.size());
    }

    /** 原始文件落盘路径：{upload-dir}/{documentId}.{fileType} */
    public Path filePath(KnowledgeDocument doc) {
        return uploadDir.resolve(doc.getId() + "." + doc.getFileType());
    }

    private void markFailed(Long documentId, String message) {
        String truncated = message == null ? "未知错误"
                : (message.length() > 500 ? message.substring(0, 500) : message);
        updateStatus(documentId, KnowledgeDocument.STATUS_FAILED, truncated);
    }

    private void updateStatus(Long documentId, String status, String errorMsg) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setErrorMsg(errorMsg);
        documentMapper.updateById(update);
    }
}
