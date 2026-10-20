package com.hify.common.exception;

import lombok.Getter;

/**
 * 业务异常。
 * 携带 {@link ErrorCode} 定位错误码；默认 message 取枚举定义，
 * 需要补充上下文时（如带上具体参数名）可传入自定义 message 覆盖。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
