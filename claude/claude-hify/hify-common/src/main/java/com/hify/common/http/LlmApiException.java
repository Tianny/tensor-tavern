package com.hify.common.http;

import lombok.Getter;

/**
 * LLM API 调用异常。携带失败分类 {@link Kind}，供上层决定重试策略：
 * 网络抖动（TIMEOUT / CALL_FAILED）可重试，认证失败不重试，限流退避重试。
 *
 * <p>注意：这是 HTTP 层的基础设施异常，不带业务错误码。
 * Provider 层捕获后按 Kind 翻译成 Provider 模块的 ErrorCode（2000 段）抛 BizException，
 * 未被翻译而泄漏到 Controller 时，走全局兜底按系统内部错误处理。
 */
@Getter
public class LlmApiException extends RuntimeException {

    /** 失败分类 */
    public enum Kind {
        /** 超时：连接超时、读超时（含流式相邻 chunk 间隔超时） */
        TIMEOUT,
        /** 认证失败：401 / 403，密钥错误或失效，不应重试 */
        AUTH_FAILED,
        /** 限流：429，应退避后重试 */
        RATE_LIMITED,
        /** 其他调用失败：网络不可达、5xx 等 */
        CALL_FAILED
    }

    private final Kind kind;

    /** 目标 URL（排障用） */
    private final String url;

    /** HTTP 状态码；网络层异常时为 null */
    private final Integer statusCode;

    public LlmApiException(Kind kind, String url, Integer statusCode, String message) {
        super(message);
        this.kind = kind;
        this.url = url;
        this.statusCode = statusCode;
    }

    public LlmApiException(Kind kind, String url, Integer statusCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.url = url;
        this.statusCode = statusCode;
    }
}
