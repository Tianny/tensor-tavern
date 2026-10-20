package com.hify.knowledge.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.http.LlmHttpClient;
import com.hify.common.resilience.CircuitBreakerService;
import com.hify.knowledge.constant.KnowledgeErrorCode;
import com.hify.knowledge.vector.PgVectorStore;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Embedding 客户端：OpenAI 兼容 embeddings 端点（POST {base}/embeddings）。
 * bailian 兼容模式（text-embedding-v3）、OpenAI 官方均适用；claude 无 embeddings API。
 *
 * <p>批量分批请求（服务商单次条数限制）；调用量小且是同步完整响应，套 provider 级
 * 熔断器 + 默认分类重试（超时重试 / 限流退避），复用对话链路同款体系。
 */
@Slf4j
@Service
public class EmbeddingClient {

    private static final String BREAKER_PREFIX = "provider:";

    private final LlmHttpClient llmHttpClient;

    private final CircuitBreakerService circuitBreakerService;

    private final ObjectMapper objectMapper;

    private final int batchSize;

    public EmbeddingClient(
            @Qualifier("llmHttpClient") LlmHttpClient llmHttpClient,
            CircuitBreakerService circuitBreakerService,
            ObjectMapper objectMapper,
            @Value("${hify.knowledge.embedding-batch-size:10}") int batchSize) {
        this.llmHttpClient = llmHttpClient;
        this.circuitBreakerService = circuitBreakerService;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, batchSize);
    }

    /** 一批文本 → 向量（顺序与输入一致）。向量维度不对直接判失败（库里全是 1024 维） */
    public List<float[]> embed(Provider provider, ModelConfig model, List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            result.addAll(embedBatch(provider, model, batch));
        }
        return result;
    }

    private List<float[]> embedBatch(Provider provider, ModelConfig model, List<String> batch) {
        String url = provider.getBaseUrl() + "/embeddings";
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + provider.getApiKey());
        Map<String, Object> body = Map.of(
                "model", model.getModelName(),
                "input", batch);

        String resp = circuitBreakerService.execute(
                BREAKER_PREFIX + provider.getId(),
                () -> llmHttpClient.post(url, headers, body));
        return parse(resp, batch.size());
    }

    private List<float[]> parse(String resp, int expected) {
        JsonNode root;
        try {
            root = objectMapper.readTree(resp);
        } catch (Exception e) {
            throw new com.hify.common.exception.BizException(
                    KnowledgeErrorCode.KNOWLEDGE_EMBEDDING_FAILED, "embedding 响应解析失败");
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != expected) {
            throw new com.hify.common.exception.BizException(
                    KnowledgeErrorCode.KNOWLEDGE_EMBEDDING_FAILED,
                    "embedding 响应条数不符：" + (data == null ? 0 : data.size()) + "/" + expected);
        }
        float[][] ordered = new float[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode embedding = item.get("embedding");
            if (index < 0 || index >= expected || embedding == null || !embedding.isArray()) {
                throw new com.hify.common.exception.BizException(
                        KnowledgeErrorCode.KNOWLEDGE_EMBEDDING_FAILED, "embedding 响应结构异常");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            if (vector.length != PgVectorStore.EMBEDDING_DIM) {
                throw new com.hify.common.exception.BizException(
                        KnowledgeErrorCode.KNOWLEDGE_EMBEDDING_FAILED,
                        "向量维度不符：" + vector.length + "（库内固定 " + PgVectorStore.EMBEDDING_DIM + " 维）");
            }
            ordered[index] = vector;
        }
        List<float[]> result = new ArrayList<>(expected);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new com.hify.common.exception.BizException(
                        KnowledgeErrorCode.KNOWLEDGE_EMBEDDING_FAILED, "embedding 响应缺序号");
            }
            result.add(vector);
        }
        return result;
    }
}
