package com.hify.common.exception;

import java.util.stream.Collectors;

import com.hify.common.dto.Result;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理器。
 * 所有异常统一转为 {@link Result} 响应，错误码走 {@link ErrorCode}，
 * HTTP 状态码恒为 200，业务状态通过 body 中的 code 字段表达。
 * 客户端参数类错误（校验失败、类型不匹配、缺参、body 格式非法）一律归 PARAM_ERROR 段，
 * 不落入兜底 handler，避免误报系统内部错误。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：取异常携带的 ErrorCode，message 用覆盖后的值 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常 code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    /** @RequestBody DTO 校验异常：取第一个字段错误拼接明细 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? CommonErrorCode.PARAM_ERROR.getMessage()
                : CommonErrorCode.PARAM_ERROR.getMessage() + ": " + fieldError.getField() + " " + fieldError.getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return Result.fail(CommonErrorCode.PARAM_ERROR.getCode(), message);
    }

    /** @RequestParam / @PathVariable 校验异常（类上 @Validated + 参数注解触发） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> lastSegment(v.getPropertyPath().toString()) + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        String message = CommonErrorCode.PARAM_ERROR.getMessage() + ": " + detail;
        log.warn("参数校验失败: {}", message);
        return Result.fail(CommonErrorCode.PARAM_ERROR.getCode(), message);
    }

    /** query 参数类型不匹配，如 ?page=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String required = e.getRequiredType() == null ? "未知" : e.getRequiredType().getSimpleName();
        String message = CommonErrorCode.PARAM_ERROR.getMessage() + ": " + e.getName() + " 应为 " + required + " 类型";
        log.warn("参数类型不匹配: {}", message);
        return Result.fail(CommonErrorCode.PARAM_ERROR.getCode(), message);
    }

    /** 必填 query 参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String message = CommonErrorCode.PARAM_ERROR.getMessage() + ": 缺少必填参数 " + e.getParameterName();
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Result.fail(CommonErrorCode.PARAM_ERROR.getCode(), message);
    }

    /** 请求体不可解析：JSON 语法错误、无法反序列化。原始异常信息不透出（可能含内部细节） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(CommonErrorCode.PARAM_BODY_ERROR);
    }

    /** 兜底：不向客户端透出内部细节，只记日志 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统内部错误", e);
        return Result.fail(CommonErrorCode.SYSTEM_ERROR);
    }

    /** 属性路径取最后一段：方法级校验的 path 形如 "list.page"，取参数名即可 */
    private static String lastSegment(String path) {
        int idx = path.lastIndexOf('.');
        return idx < 0 ? path : path.substring(idx + 1);
    }
}
