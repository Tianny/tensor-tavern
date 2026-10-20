package com.hify.knowledge.dto;

import lombok.Data;

/**
 * 检索命中（含来源信息，检索测试面板与引用提示共用）。
 */
@Data
public class RetrievalHit {

    private Integer rank;

    private Long documentId;

    /** 来源文档名 */
    private String documentName;

    private Integer chunkIndex;

    private String content;

    /** 余弦相似度 [0,1] */
    private Double score;
}
