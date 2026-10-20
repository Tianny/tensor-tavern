package com.hify.common.util;

/**
 * token 数粗估：CJK 字符按 1:1，其他按 4 字符 1 token。
 * 用于上下文预算、分块目标大小等粗粒度场景，不做精确分词。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF     // CJK 统一表意 + 常用区
                    || c >= 0x3000 && c <= 0x303F   // CJK 标点
                    || c >= 0xFF00 && c <= 0xFFEF) {  // 全角字符
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }
}
