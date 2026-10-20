package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_base")
public class KnowledgeBase extends BaseEntity {

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 向量化模型 model_config.id（model_type=embedding），一个库固定一个 embedding 模型 */
    private Long embeddingModelId;
}
