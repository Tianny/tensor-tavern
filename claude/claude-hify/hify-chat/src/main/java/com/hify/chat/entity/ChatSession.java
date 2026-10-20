package com.hify.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话会话。主键即 sessionId，对应 Redis 上下文 key session:{id}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_session")
public class ChatSession extends BaseEntity {

    /** 关联的 agent.id；工作流会话为 null */
    private Long agentId;

    /** 关联的 workflow.id；Agent 会话为 null */
    private Long workflowId;

    /** 会话标题（首条用户消息截断自动生成） */
    private String title;
}
