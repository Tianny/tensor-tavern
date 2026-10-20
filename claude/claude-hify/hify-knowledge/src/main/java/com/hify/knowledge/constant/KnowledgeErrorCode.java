package com.hify.knowledge.constant;

import com.hify.common.exception.ErrorCode;

import lombok.Getter;

/**
 * Knowledge 模块错误码（7000-7999 段）。
 */
@Getter
public enum KnowledgeErrorCode implements ErrorCode {

    /** 知识库不存在 */
    KNOWLEDGE_BASE_NOT_FOUND(7001, "知识库不存在"),

    /** 知识库名称重复 */
    KNOWLEDGE_BASE_NAME_DUPLICATED(7002, "知识库名称已存在"),

    /** 绑定的向量化模型不可用（不存在 / 停用 / 类型不对 / 提供商不支持 embeddings 端点） */
    KNOWLEDGE_EMBEDDING_MODEL_INVALID(7003, "向量化模型不可用或不是 embedding 类型"),

    /** 文档不存在 */
    KNOWLEDGE_DOCUMENT_NOT_FOUND(7004, "文档不存在"),

    /** 文件类型不支持（当前仅 txt / md 纯文本） */
    KNOWLEDGE_FILE_TYPE_NOT_SUPPORTED(7005, "文件类型不支持，当前仅支持 txt / md 纯文本"),

    /** 文件为空或超过大小上限 */
    KNOWLEDGE_FILE_INVALID(7006, "文件为空或超过大小上限"),

    /** 文档状态不允许该操作（如非失败态重试） */
    KNOWLEDGE_DOCUMENT_STATUS_INVALID(7007, "文档当前状态不允许该操作"),

    /** 向量化失败（解析 / 分块 / embedding 调用） */
    KNOWLEDGE_EMBEDDING_FAILED(7008, "文档向量化失败");

    private final int code;

    private final String message;

    KnowledgeErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
