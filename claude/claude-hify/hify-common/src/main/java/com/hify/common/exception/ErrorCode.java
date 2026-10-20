package com.hify.common.exception;

/**
 * 错误码接口：四位数字，按模块分段。
 * <pre>
 * 1000-1999  通用（CommonErrorCode）
 * 2000-2999  Provider
 * 3000-3999  Agent
 * 4000-4999  Chat
 * 5000-5999  MCP
 * 6000-6999  Workflow
 * 7000-7999  Knowledge
 * </pre>
 * 各业务模块的 ErrorCode 枚举在各自模块的 constant 包下定义并实现本接口；
 * {@link BizException} 只收本接口，通用与模块错误码统一走一套异常体系。
 * 错误信息禁止在业务代码中硬编码，统一走枚举。
 */
public interface ErrorCode {

    /** 错误码（四位数字，按模块分段） */
    int getCode();

    /** 默认错误信息 */
    String getMessage();
}
