package com.hify.common.log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;

/**
 * 生产环境 JSON 行式日志布局（手写，未引入 logstash-logback-encoder 栈外依赖）。
 * 每行一个 JSON 对象，输出到 stdout 由 K8s/容器采集：
 * <pre>
 * {"timestamp":"2026-10-17T21:30:15.123+08:00","level":"INFO","traceId":"a1b2...","thread":"main","logger":"c.h.a.H","message":"..."}
 * </pre>
 * traceId / stack 字段缺失时直接省略，不输出 null。
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public String doLayout(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"timestamp\":\"");
        appendEscaped(sb, TIMESTAMP.format(Instant.ofEpochMilli(event.getTimeStamp()).atZone(ZoneId.systemDefault())));
        sb.append("\",\"level\":\"").append(event.getLevel());
        String traceId = event.getMDCPropertyMap().get("traceId");
        if (traceId != null) {
            sb.append("\",\"traceId\":\"");
            appendEscaped(sb, traceId);
        }
        sb.append("\",\"thread\":\"");
        appendEscaped(sb, event.getThreadName());
        sb.append("\",\"logger\":\"");
        appendEscaped(sb, event.getLoggerName());
        sb.append("\",\"message\":\"");
        appendEscaped(sb, event.getFormattedMessage());

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            sb.append("\",\"stack\":\"");
            appendEscaped(sb, ThrowableProxyUtil.asString(throwable));
        }

        sb.append("\"}\n");
        return sb.toString();
    }

    /** JSON 字符串转义：引号、反斜杠、控制字符 */
    private static void appendEscaped(StringBuilder sb, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
