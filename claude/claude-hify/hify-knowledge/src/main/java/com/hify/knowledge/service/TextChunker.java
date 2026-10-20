package com.hify.knowledge.service;

import java.util.ArrayList;
import java.util.List;

import com.hify.common.util.TokenEstimator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 纯文本分块：按句子贪心装箱到目标大小，相邻块保留重叠。
 *
 * <p>切分点：CJK 句末标点（。！？；…）直接断句；西文句点 / 问号 / 叹号后跟空白或结尾才断
 * （避开 "3.14"、"e.g." 被切碎）；换行视为强断点（markdown 的标题、列表行天然成句）。
 * 超过块上限的单句按字符硬切。块大小用 token 估算值（TokenEstimator 同款启发式）。
 */
@Component
public class TextChunker {

    private final int chunkSizeTokens;

    private final int overlapTokens;

    public TextChunker(
            @Value("${hify.knowledge.chunk-size-tokens:400}") int chunkSizeTokens,
            @Value("${hify.knowledge.chunk-overlap-tokens:60}") int overlapTokens) {
        this.chunkSizeTokens = chunkSizeTokens;
        this.overlapTokens = overlapTokens;
    }

    /** 分块结果 */
    public record Chunk(String content, int tokenCount) {
    }

    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        List<String> sentences = splitSentences(text);

        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        String overlapCarry = "";
        for (String sentence : sentences) {
            int sentenceTokens = TokenEstimator.estimate(sentence);
            if (sentenceTokens > chunkSizeTokens) {
                // 超长句：先收口当前块，再按字符硬切
                if (current.length() > 0) {
                    chunks.add(build(current));
                    overlapCarry = overlapTail(current.toString());
                    current = new StringBuilder(overlapCarry);
                    currentTokens = TokenEstimator.estimate(overlapCarry);
                }
                for (String piece : hardSplit(sentence)) {
                    chunks.add(build(new StringBuilder(piece)));
                }
                overlapCarry = "";
                current.setLength(0);
                currentTokens = 0;
                continue;
            }
            if (currentTokens + sentenceTokens > chunkSizeTokens && current.length() > 0) {
                chunks.add(build(current));
                overlapCarry = overlapTail(current.toString());
                current = new StringBuilder(overlapCarry);
                currentTokens = TokenEstimator.estimate(overlapCarry);
            }
            current.append(sentence);
            currentTokens += sentenceTokens;
        }
        if (current.length() > 0) {
            chunks.add(build(current));
        }
        return chunks;
    }

    /** 把文本切成句（保留切分符） */
    private List<String> splitSentences(String text) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").replace("﻿", "");
        List<String> sentences = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            sb.append(c);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；' || c == '…') {
                sentences.add(sb.toString());
                sb.setLength(0);
            } else if ((c == '.' || c == '!' || c == '?' || c == ';')
                    && isSentenceBoundary(normalized, i)) {
                sentences.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            sentences.add(sb.toString());
        }
        // 吸掉句子间残余空白到前句尾部，避免块之间夹纯空白串
        return sentences.stream().filter(s -> !s.isBlank()).toList();
    }

    /** 西文句点边界：后面是空白 / 行尾 / 引号收尾才算句末 */
    private boolean isSentenceBoundary(String text, int dotIndex) {
        int next = dotIndex + 1;
        if (next >= text.length()) {
            return true;
        }
        char c = text.charAt(next);
        return c == ' ' || c == '\n' || c == '\t' || c == '"' || c == '”' || c == '\'';
    }

    /** 超长句硬切：按块上限的字符近似（CJK 1:1，保守取 token 数等同字符数） */
    private List<String> hardSplit(String sentence) {
        List<String> pieces = new ArrayList<>();
        int maxChars = chunkSizeTokens * 2;   // 纯西文 1 token≈4 字符，2 倍上限内一定装得下
        for (int i = 0; i < sentence.length(); i += maxChars) {
            pieces.add(sentence.substring(i, Math.min(i + maxChars, sentence.length())));
        }
        return pieces;
    }

    /** 取上一块尾部约 overlapTokens 的内容，接到下一块开头 */
    private String overlapTail(String chunk) {
        if (overlapTokens <= 0 || chunk.isEmpty()) {
            return "";
        }
        int tailChars = Math.min(chunk.length(), overlapTokens * 2 + 20);
        String tail = chunk.substring(chunk.length() - tailChars);
        // 从整句边界修剪（找第一个换行 / 句末标点），避免半截句子开头
        int cut = -1;
        for (int i = 1; i < tail.length(); i++) {
            char c = tail.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == ';') {
                cut = i;
            }
        }
        return cut > 0 ? tail.substring(cut) : tail;
    }

    private Chunk build(StringBuilder sb) {
        String content = sb.toString().strip();
        return new Chunk(content, TokenEstimator.estimate(content));
    }
}
