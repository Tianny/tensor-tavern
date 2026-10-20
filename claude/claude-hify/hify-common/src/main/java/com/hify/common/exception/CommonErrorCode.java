package com.hify.common.exception;

import lombok.Getter;

/**
 * 通用错误码（1000-1999 段）。
 * 业务模块的错误码在各模块 constant 包下实现 {@link ErrorCode} 接口，勿加到这里。
 */
@Getter
public enum CommonErrorCode implements ErrorCode {

    /** 参数错误：校验不通过、格式非法等 */
    PARAM_ERROR(1000, "参数错误"),

    /** 未授权：未登录、凭证失效 */
    UNAUTHORIZED(1001, "未授权"),

    /** 资源不存在：按 id 查询为空 */
    NOT_FOUND(1002, "资源不存在"),

    /** 系统内部错误：未预期的异常 */
    SYSTEM_ERROR(1003, "系统内部错误"),

    /** 服务暂不可用：依赖组件故障、熔断开启 */
    SERVICE_UNAVAILABLE(1004, "服务暂不可用"),

    /** 请求体格式错误：JSON 语法非法、无法反序列化 */
    PARAM_BODY_ERROR(1005, "请求体格式错误");

    private final int code;

    private final String message;

    CommonErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
