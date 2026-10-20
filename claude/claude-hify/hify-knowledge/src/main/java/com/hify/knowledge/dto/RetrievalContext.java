package com.hify.knowledge.dto;

import java.util.Collections;
import java.util.List;

import lombok.Data;

/**
 * 一次 RAG 检索的结果：命中明细 + 已拼装好的参考资料 prompt 片段。
 * 无命中时 hits 为空、prompt 为空串，对话退化为直答。
 */
@Data
public class RetrievalContext {

    private List<RetrievalHit> hits = Collections.emptyList();

    private String prompt = "";

    public boolean hasHits() {
        return hits != null && !hits.isEmpty();
    }
}
