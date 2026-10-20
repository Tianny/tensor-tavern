package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型配置：一个提供商下的具体模型（chat / embedding）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_config")
public class ModelConfig extends BaseEntity {

    /** 所属提供商 provider.id */
    private Long providerId;

    /** 模型标识，如 gpt-4o / deepseek-chat */
    private String modelName;

    /** 展示名称 */
    private String displayName;

    /** 模型类型：chat（对话）/ embedding（向量化） */
    private String modelType;

    /** 上下文窗口 token 上限，0=未知/不限 */
    private Integer maxContextTokens;

    /** 是否启用：0=停用 1=启用 */
    private Integer enabled;
}
