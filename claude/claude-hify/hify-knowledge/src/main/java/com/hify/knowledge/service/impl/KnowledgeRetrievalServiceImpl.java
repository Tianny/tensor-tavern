package com.hify.knowledge.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.knowledge.dto.RetrievalContext;
import com.hify.knowledge.dto.RetrievalHit;
import com.hify.knowledge.entity.KnowledgeBase;
import com.hify.knowledge.entity.KnowledgeDocument;
import com.hify.knowledge.mapper.KnowledgeDocumentMapper;
import com.hify.knowledge.service.EmbeddingClient;
import com.hify.knowledge.service.KnowledgeBaseService;
import com.hify.knowledge.service.KnowledgeRetrievalService;
import com.hify.knowledge.vector.PgVectorStore;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * RAG 检索实现。Agent 绑多个库时按 embedding 模型分组（不同库可能绑不同模型，
 * 向量空间不同不能混检），组内检索后按相似度合并取 topK。
 *
 * <p>失败语义：embedding 模型不可用的库跳过（warn），检索整体失败向上抛，
 * 由调用方（chat）决定是否降级为直答。
 */
@Slf4j
@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;

    private final KnowledgeDocumentMapper documentMapper;

    private final ModelConfigService modelConfigService;

    private final ProviderService providerService;

    private final EmbeddingClient embeddingClient;

    private final PgVectorStore vectorStore;

    private final int topK;

    private final double minScore;

    public KnowledgeRetrievalServiceImpl(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeDocumentMapper documentMapper,
            ModelConfigService modelConfigService,
            ProviderService providerService,
            EmbeddingClient embeddingClient,
            PgVectorStore vectorStore,
            @Value("${hify.knowledge.retrieval-top-k:4}") int topK,
            @Value("${hify.knowledge.retrieval-min-score:0.30}") double minScore) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentMapper = documentMapper;
        this.modelConfigService = modelConfigService;
        this.providerService = providerService;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.minScore = minScore;
    }

    @Override
    public RetrievalContext retrieveForAgent(Long agentId, String query) {
        List<Long> kbIds = knowledgeBaseService.listBoundKnowledgeBaseIds(agentId);
        if (kbIds.isEmpty()) {
            return emptyContext();
        }
        List<PgVectorStore.ChunkHit> hits = searchKnowledgeBases(kbIds, query, topK);
        return buildContext(hits);
    }

    @Override
    public List<RetrievalHit> search(Long knowledgeBaseId, String query, Integer topK) {
        knowledgeBaseService.getEntity(knowledgeBaseId);
        int k = topK == null ? this.topK : topK;
        List<PgVectorStore.ChunkHit> hits = searchKnowledgeBases(List.of(knowledgeBaseId), query, k);
        return toHits(hits);
    }

    // ---------- 内部 ----------

    /** 跨库检索：按 embedding 模型分组 → 分组检索 → 合并排序过滤 */
    private List<PgVectorStore.ChunkHit> searchKnowledgeBases(List<Long> kbIds, String query, int k) {
        Map<Long, KnowledgeBase> kbs = new LinkedHashMap<>();
        for (Long kbId : kbIds) {
            kbs.put(kbId, knowledgeBaseService.getEntity(kbId));
        }
        // 分组：embedding 模型 → 库 id 列表
        Map<Long, List<Long>> groups = new HashMap<>();
        for (KnowledgeBase kb : kbs.values()) {
            groups.computeIfAbsent(kb.getEmbeddingModelId(), id -> new ArrayList<>()).add(kb.getId());
        }

        List<PgVectorStore.ChunkHit> merged = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> group : groups.entrySet()) {
            ModelConfig model = modelConfigService.getById(group.getKey());
            if (model == null || model.getEnabled() != 1) {
                log.warn("知识库 embedding 模型不可用，跳过该组 modelId={} kbIds={}", group.getKey(), group.getValue());
                continue;
            }
            Provider provider = providerService.getEntity(model.getProviderId());
            if (provider.getEnabled() != 1) {
                log.warn("知识库 embedding 提供商已停用，跳过该组 provider={} kbIds={}",
                        provider.getName(), group.getValue());
                continue;
            }
            float[] queryVector = embeddingClient.embed(provider, model, List.of(query)).get(0);
            merged.addAll(vectorStore.search(queryVector, group.getValue(), k));
        }
        merged.sort(Comparator.comparingDouble(PgVectorStore.ChunkHit::score).reversed());
        return merged.stream()
                .filter(hit -> hit.score() >= minScore)
                .limit(k)
                .toList();
    }

    /** 命中明细 + 引用 prompt 片段 */
    private RetrievalContext buildContext(List<PgVectorStore.ChunkHit> hits) {
        RetrievalContext context = new RetrievalContext();
        if (hits.isEmpty()) {
            return context;
        }
        context.setHits(toHits(hits));
        context.setPrompt(buildPrompt(hits));
        return context;
    }

    private RetrievalContext emptyContext() {
        return new RetrievalContext();
    }

    private List<RetrievalHit> toHits(List<PgVectorStore.ChunkHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        // 来源文档名（文档被删则空串）
        Map<Long, String> names = documentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeDocument>().in(KnowledgeDocument::getId,
                                hits.stream().map(PgVectorStore.ChunkHit::documentId).distinct().toList()))
                .stream().collect(Collectors.toMap(KnowledgeDocument::getId,
                        d -> d.getFileName() == null ? "" : d.getFileName()));
        List<RetrievalHit> result = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            PgVectorStore.ChunkHit hit = hits.get(i);
            RetrievalHit resp = new RetrievalHit();
            resp.setRank(i + 1);
            resp.setDocumentId(hit.documentId());
            resp.setDocumentName(names.getOrDefault(hit.documentId(), ""));
            resp.setChunkIndex(hit.chunkIndex());
            resp.setContent(hit.content());
            resp.setScore(hit.score());
            result.add(resp);
        }
        return result;
    }

    /** 引用编号从 1 开始，与命中排序一致 */
    private String buildPrompt(List<PgVectorStore.ChunkHit> hits) {
        StringBuilder sb = new StringBuilder("以下是回答用户问题时可参考的知识库资料：\n\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append("【").append(i + 1).append("】").append(hits.get(i).content()).append("\n\n");
        }
        sb.append("回答要求：优先依据上述资料回答用户问题；")
                .append("资料未覆盖的内容如实说明；")
                .append("引用资料时在相关语句末尾标注编号，如 [1]。");
        return sb.toString();
    }
}
