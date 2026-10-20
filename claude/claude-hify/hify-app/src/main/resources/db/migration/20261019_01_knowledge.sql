-- 变更脚本：knowledge 模块落地（RAG）
-- 1. 新增 agent_knowledge：Agent 与知识库绑定（M:N，应用层维护）
-- 2. 删除 document_chunk：向量与分块全文改存 pgvector（PG 表 document_chunk，应用启动时自动建表）
-- 注意：schema.sql 已同步为最新结构，新环境直接执行 schema.sql 即可
CREATE TABLE agent_knowledge (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  agent_id          BIGINT       NOT NULL COMMENT 'Agent agent.id',
  knowledge_base_id BIGINT       NOT NULL COMMENT '知识库 knowledge_base.id',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_knowledge (agent_id, knowledge_base_id),
  KEY idx_agent_knowledge_agent_id (agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 与知识库绑定';

DROP TABLE IF EXISTS document_chunk;
