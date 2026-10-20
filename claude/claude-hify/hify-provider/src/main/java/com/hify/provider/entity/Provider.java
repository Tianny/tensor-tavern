package com.hify.provider.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型提供商。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("provider")
public class Provider extends BaseEntity {

    /** 提供商名称，如 OpenAI */
    private String name;

    /** 提供商类型：{@link com.hify.provider.constant.ProviderType} 的 code */
    private String type;

    /** API 地址（含版本前缀），空串表示用官方默认地址 */
    private String baseUrl;

    /** API 密钥；本地部署可为空。响应 DTO 中永不回传明文，只回掩码 */
    private String apiKey;

    /** 是否启用：0=停用 1=启用 */
    private Integer enabled;

    /** 备注 */
    private String remark;

    /** 最近一次连通性测试时间，null=从未测试 */
    private LocalDateTime lastTestAt;

    /** 最近一次连通性测试结果：0=失败 1=成功，null=从未测试 */
    private Integer lastTestOk;

    /** 最近一次连通性测试信息（失败原因等） */
    private String lastTestMessage;
}
