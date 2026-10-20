package com.hify.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话消息（增长最快的表，分页走 id 游标 + idx_chat_message_session_id）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    /** 所属会话 chat_session.id */
    private Long sessionId;

    /** user / assistant（system 每次从 Agent 配置现取，不落库） */
    private String role;

    /** 消息内容 */
    private String content;

    /** 输入 token 数（user 消息为 0） */
    private Integer promptTokens;

    /** 输出 token 数 */
    private Integer completionTokens;

    /** LLM 响应耗时（毫秒） */
    private Integer latencyMs;
}
