package com.hify.workflow.service.impl;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hify.common.exception.BizException;
import com.hify.workflow.constant.WorkflowErrorCode;

import org.springframework.stereotype.Component;

/**
 * 条件边表达式求值器。语法（手写解析，不引表达式引擎，符合"最简单直接"）：
 *
 * <pre>
 * ${key} == "文本"      相等（字符串比较）
 * ${key} != "文本"      不等
 * ${key} contains "文本" 包含子串
 * ${key}                非空判断（值存在且非空白）
 * </pre>
 *
 * key 为变量池引用：{@code start.变量名} 或 {@code 节点Key.output}。
 * 字符串字面量须用英文双引号包裹；数值按字符串比较（简版不做类型推断）。
 */
@Component
public class ConditionEvaluator {

    /** 单个变量引用，如 ${start.query} */
    private static final Pattern REF = Pattern.compile("\\$\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}");

    /** 完整比较表达式：引用 + 操作符 + 双引号字面量 */
    private static final Pattern COMPARISON = Pattern.compile(
            "^\\$\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*\\}\\s*(==|!=|contains)\\s*\"([^\"]*)\"$");

    /**
     * 求值。表达式语法错误抛 6008（保存时校验 + 运行时兜底共用）。
     */
    public boolean evaluate(String expr, Map<String, String> pool) {
        if (expr == null || expr.isBlank()) {
            throw exprError("条件表达式为空");
        }
        String trimmed = expr.trim();

        Matcher comparison = COMPARISON.matcher(trimmed);
        if (comparison.matches()) {
            String value = pool.getOrDefault(comparison.group(1), "");
            String literal = comparison.group(3);
            return switch (comparison.group(2)) {
                case "==" -> value.equals(literal);
                case "!=" -> !value.equals(literal);
                case "contains" -> value.contains(literal);
                default -> throw exprError("不支持的操作符：" + comparison.group(2));
            };
        }

        Matcher bare = REF.matcher(trimmed);
        if (bare.matches()) {
            String value = pool.get(bare.group(1));
            return value != null && !value.isBlank();
        }

        throw exprError("条件表达式语法不合法：" + trimmed);
    }

    /**
     * 保存时静态校验语法（不求值）。
     */
    public void validateSyntax(String expr) {
        evaluate(expr, Map.of());
    }

    private BizException exprError(String detail) {
        return new BizException(WorkflowErrorCode.WORKFLOW_EXPR_INVALID, detail);
    }
}
