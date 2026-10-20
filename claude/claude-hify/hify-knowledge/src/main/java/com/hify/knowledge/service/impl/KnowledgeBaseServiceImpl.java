package com.hify.knowledge.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeErrorCode;
import com.hify.knowledge.dto.AgentKnowledgeUpdateReq;
import com.hify.knowledge.dto.KnowledgeBaseCreateReq;
import com.hify.knowledge.dto.KnowledgeBaseResp;
import com.hify.knowledge.dto.KnowledgeBaseUpdateReq;
import com.hify.knowledge.entity.AgentKnowledge;
import com.hify.knowledge.entity.KnowledgeBase;
import com.hify.knowledge.entity.KnowledgeDocument;
import com.hify.knowledge.mapper.AgentKnowledgeMapper;
import com.hify.knowledge.mapper.KnowledgeBaseMapper;
import com.hify.knowledge.mapper.KnowledgeDocumentMapper;
import com.hify.knowledge.service.KnowledgeBaseService;
import com.hify.knowledge.vector.PgVectorStore;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识库管理。embedding 模型一旦建库不可换（向量维度与模型绑定，换模型等于全量重刷），
 * 所以 create 校验、update 不允许改模型。绑定关系行纯关系数据，物理删除
 * （逻辑删除会占住 uk_agent_knowledge 唯一键，重复绑定直接冲突）。
 */
@Slf4j
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    /** 支持 embeddings 端点的提供商类型（claude 无 embeddings API，gemini/ollama 未接入） */
    private static final Set<String> EMBEDDING_SUPPORTED_TYPES = Set.of("openai", "openai_compatible");

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeDocumentMapper documentMapper;

    private final AgentKnowledgeMapper agentKnowledgeMapper;

    private final PgVectorStore vectorStore;

    private final DocumentPipeline documentPipeline;

    private final ModelConfigService modelConfigService;

    private final ProviderService providerService;

    public KnowledgeBaseServiceImpl(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeDocumentMapper documentMapper,
            AgentKnowledgeMapper agentKnowledgeMapper,
            PgVectorStore vectorStore,
            DocumentPipeline documentPipeline,
            ModelConfigService modelConfigService,
            ProviderService providerService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.agentKnowledgeMapper = agentKnowledgeMapper;
        this.vectorStore = vectorStore;
        this.documentPipeline = documentPipeline;
        this.modelConfigService = modelConfigService;
        this.providerService = providerService;
    }

    @Override
    public List<KnowledgeBaseResp> list() {
        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>().orderByDesc(KnowledgeBase::getId));
        if (kbs.isEmpty()) {
            return List.of();
        }
        // 批量联表模型 / 提供商名（模型被删时展示空串）
        Map<Long, ModelConfig> models = modelConfigService.listByIds(
                        kbs.stream().map(KnowledgeBase::getEmbeddingModelId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ModelConfig::getId, Function.identity()));
        Set<Long> providerIds = models.values().stream()
                .map(ModelConfig::getProviderId).collect(Collectors.toSet());
        Map<Long, Provider> providers = providerIds.isEmpty() ? Map.of()
                : providerService.listByIds(providerIds).stream()
                        .collect(Collectors.toMap(Provider::getId, Function.identity()));
        return kbs.stream().map(kb -> {
            ModelConfig model = models.get(kb.getEmbeddingModelId());
            Provider provider = model == null ? null : providers.get(model.getProviderId());
            return KnowledgeBaseResp.of(kb,
                    model == null ? "" : model.getDisplayName(),
                    provider == null ? "" : provider.getName());
        }).toList();
    }

    @Override
    public KnowledgeBaseResp create(KnowledgeBaseCreateReq req) {
        checkNameDuplicate(req.getName(), null);
        validateEmbeddingModel(req.getEmbeddingModelId());
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName().trim());
        kb.setDescription(req.getDescription() == null ? "" : req.getDescription().trim());
        kb.setEmbeddingModelId(req.getEmbeddingModelId());
        knowledgeBaseMapper.insert(kb);
        return getResp(kb.getId());
    }

    @Override
    public KnowledgeBaseResp update(Long id, KnowledgeBaseUpdateReq req) {
        getEntity(id);
        checkNameDuplicate(req.getName(), id);
        KnowledgeBase update = new KnowledgeBase();
        update.setId(id);
        update.setName(req.getName().trim());
        update.setDescription(req.getDescription() == null ? "" : req.getDescription().trim());
        knowledgeBaseMapper.updateById(update);
        return getResp(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        KnowledgeBase kb = getEntity(id);
        // 库删除：元数据逻辑删除；向量物理清掉（pg 无逻辑删除，留着就是孤儿数据）；
        // 原始文件随文档一起清（不可达即无重刷价值）
        List<KnowledgeDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKnowledgeBaseId, id));
        for (KnowledgeDocument doc : docs) {
            documentMapper.deleteById(doc.getId());
            vectorStore.deleteByDocument(doc.getId());
            try {
                java.nio.file.Files.deleteIfExists(documentPipeline.filePath(doc));
            } catch (java.io.IOException e) {
                log.warn("知识库删除时原始文件清理失败（不影响功能） documentId={} 原因={}",
                        doc.getId(), e.getMessage());
            }
        }
        agentKnowledgeMapper.physicalDeleteByKnowledgeBase(id);
        knowledgeBaseMapper.deleteById(kb.getId());
        log.info("知识库删除 id={} name={} 文档数={}", id, kb.getName(), docs.size());
    }

    @Override
    public KnowledgeBase getEntity(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在，id=" + id);
        }
        return kb;
    }

    @Override
    public List<Long> listBoundKnowledgeBaseIds(Long agentId) {
        return agentKnowledgeMapper.selectList(
                        new LambdaQueryWrapper<AgentKnowledge>().eq(AgentKnowledge::getAgentId, agentId))
                .stream().map(AgentKnowledge::getKnowledgeBaseId).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAgentBindings(Long agentId, AgentKnowledgeUpdateReq req) {
        List<Long> kbIds = req.getKnowledgeBaseIds() == null ? List.of() : req.getKnowledgeBaseIds();
        // 校验 + 去重
        Set<Long> distinct = new HashSet<>();
        for (Long kbId : kbIds) {
            getEntity(kbId);
            distinct.add(kbId);
        }
        agentKnowledgeMapper.physicalDeleteByAgent(agentId);
        for (Long kbId : distinct) {
            AgentKnowledge binding = new AgentKnowledge();
            binding.setAgentId(agentId);
            binding.setKnowledgeBaseId(kbId);
            agentKnowledgeMapper.insert(binding);
        }
    }

    // ---------- 内部 ----------

    private KnowledgeBaseResp getResp(Long id) {
        KnowledgeBase kb = getEntity(id);
        ModelConfig model = modelConfigService.getById(kb.getEmbeddingModelId());
        Provider provider = model == null ? null : providerService.getEntity(model.getProviderId());
        return KnowledgeBaseResp.of(kb,
                model == null ? "" : model.getDisplayName(),
                provider == null ? "" : provider.getName());
    }

    private void checkNameDuplicate(String name, Long excludeId) {
        KnowledgeBase existing = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getName, name.trim()).last("LIMIT 1"));
        if (existing != null && !existing.getId().equals(excludeId)) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_BASE_NAME_DUPLICATED, "知识库名称已存在：" + name);
        }
    }

    private void validateEmbeddingModel(Long modelId) {
        ModelConfig model = modelConfigService.getById(modelId);
        if (model == null || model.getEnabled() != 1 || !"embedding".equals(model.getModelType())) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_EMBEDDING_MODEL_INVALID,
                    "向量化模型不可用或不是 embedding 类型，id=" + modelId);
        }
        Provider provider = providerService.getEntity(model.getProviderId());
        if (provider.getEnabled() != 1 || !EMBEDDING_SUPPORTED_TYPES.contains(provider.getType())) {
            throw new BizException(KnowledgeErrorCode.KNOWLEDGE_EMBEDDING_MODEL_INVALID,
                    "提供商不可用或不支持 embeddings：" + provider.getName());
        }
    }
}
