-- 变更脚本：对话页直接选工作流开聊
-- 1. chat_session.agent_id 改可空（工作流会话无 Agent）
-- 2. chat_session 新增 workflow_id（工作流会话的入口工作流）
-- 注意：schema.sql 已同步为最新结构，新环境直接执行 schema.sql 即可
ALTER TABLE chat_session
  MODIFY COLUMN agent_id BIGINT NULL COMMENT '关联的 agent.id，工作流会话为 NULL',
  ADD COLUMN workflow_id BIGINT NULL COMMENT '关联的 workflow.id，Agent 会话为 NULL' AFTER agent_id,
  ADD KEY idx_chat_session_workflow_id (workflow_id);
