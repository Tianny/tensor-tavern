package com.hify.common.dto;

import com.hify.common.exception.ErrorCode;

import lombok.Data;

/**
 * 统一响应封装。
 * <pre>
 * { "code": 200, "message": "success", "data": {...} }
 * </pre>
 * code：200 表示成功；错误码为四位数字，按模块分段（见接口规范）。
 *
 * @param <T> data 的类型
 */
@Data
public class Result<T> {

    public static final int SUCCESS_CODE = 200;
    public static final String SUCCESS_MESSAGE = "success";

    /** 业务状态码 */
    private int code;

    /** 提示信息，成功时为 "success" */
    private String message;

    /** 响应数据；无数据或对象不存在时为 null */
    private T data;

    protected Result() {
    }

    public static <T> Result<T> ok() {
        return build(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> Result<T> ok(T data) {
        return build(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return build(code, message, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return build(errorCode.getCode(), errorCode.getMessage(), null);
    }

    private static <T> Result<T> build(int code, String message, T data) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        result.data = data;
        return result;
    }
}
