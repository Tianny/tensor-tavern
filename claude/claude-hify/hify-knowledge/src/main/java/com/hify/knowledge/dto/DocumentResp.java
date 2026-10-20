package com.hify.knowledge.dto;

import java.time.LocalDateTime;

import com.hify.knowledge.entity.KnowledgeDocument;

import lombok.Data;

/**
 * 知识库文档响应（chunkCount 来自 pgvector 实查）。
 */
@Data
public class DocumentResp {

    private Long id;

    private Long knowledgeBaseId;

    private String fileName;

    private String fileType;

    private Long fileSize;

    /** pending / processing / completed / failed */
    private String status;

    private String errorMsg;

    /** 分块数（pgvector 实查，pending 中为 0） */
    private Integer chunkCount;

    private LocalDateTime createdAt;

    public static DocumentResp of(KnowledgeDocument doc, int chunkCount) {
        DocumentResp resp = new DocumentResp();
        resp.setId(doc.getId());
        resp.setKnowledgeBaseId(doc.getKnowledgeBaseId());
        resp.setFileName(doc.getFileName());
        resp.setFileType(doc.getFileType());
        resp.setFileSize(doc.getFileSize());
        resp.setStatus(doc.getStatus());
        resp.setErrorMsg(doc.getErrorMsg() == null ? "" : doc.getErrorMsg());
        resp.setChunkCount(chunkCount);
        resp.setCreatedAt(doc.getCreatedAt());
        return resp;
    }
}
