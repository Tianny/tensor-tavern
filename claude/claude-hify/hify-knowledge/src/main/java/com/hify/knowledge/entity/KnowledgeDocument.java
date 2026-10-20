package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文档。分块与向量不在此表（存 pgvector，见 PgVectorStore）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document")
public class KnowledgeDocument extends BaseEntity {

    /** 所属知识库 knowledge_base.id */
    private Long knowledgeBaseId;

    /** 文件名（含扩展名） */
    private String fileName;

    /** 文件类型：txt / md */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 处理状态：pending / processing / completed / failed */
    private String status;

    /** 处理失败原因 */
    private String errorMsg;

    public static final String STATUS_PENDING = "pending";

    public static final String STATUS_PROCESSING = "processing";

    public static final String STATUS_COMPLETED = "completed";

    public static final String STATUS_FAILED = "failed";
}
