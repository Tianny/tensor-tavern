package com.hify.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文消息（Redis session:{id} 里的一条）。只存 user / assistant 轮次；
 * system prompt 每次从 Agent 配置现取（改 Prompt 立即生效），不入上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextMessage {

    private String role;

    private String content;
}
