package com.hify.provider.constant;

import com.hify.common.exception.ErrorCode;

import lombok.Getter;

/**
 * Provider 模块错误码（2000-2999 段）。
 */
@Getter
public enum ProviderErrorCode implements ErrorCode {

    /** Provider 不存在 */
    PROVIDER_NOT_FOUND(2001, "Provider 不存在"),

    /** 名称重复 */
    PROVIDER_NAME_DUPLICATED(2002, "Provider 名称已存在"),

    /** type 不在支持列表 */
    PROVIDER_TYPE_INVALID(2003, "Provider 类型不支持"),

    /** OpenAI 兼容类型没有默认地址，必须显式填写 */
    PROVIDER_BASE_URL_REQUIRED(2004, "该 Provider 类型必须填写 Base URL"),

    /** openai / claude / gemini 官方端点必须有 Key */
    PROVIDER_API_KEY_REQUIRED(2005, "该 Provider 类型必须填写 API Key"),

    /** 模型配置不存在 */
    MODEL_CONFIG_NOT_FOUND(2006, "模型配置不存在"),

    /** 同一 Provider 下模型名重复 */
    MODEL_NAME_DUPLICATED(2007, "同一提供商下模型名已存在"),

    /** 拉取远端模型列表失败 */
    PROVIDER_REMOTE_MODELS_FAILED(2008, "拉取模型列表失败"),

    /** modelType 只能是 chat / embedding */
    MODEL_TYPE_INVALID(2009, "模型类型只能是 chat 或 embedding");

    private final int code;

    private final String message;

    ProviderErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
