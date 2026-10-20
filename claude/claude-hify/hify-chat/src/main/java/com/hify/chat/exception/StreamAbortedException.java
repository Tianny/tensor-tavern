package com.hify.chat.exception;

/**
 * 对话流被客户端中止（浏览器断开 / 用户点停止 / SSE 超时）。
 * 透传给熔断器忽略（application.yml ignoreExceptions），不计入 Provider 失败率；
 * 已生成的部分内容照常落库。
 */
public class StreamAbortedException extends RuntimeException {

    public StreamAbortedException(Throwable cause) {
        super("对话流被客户端中止：" + cause.getMessage(), cause);
    }
}
