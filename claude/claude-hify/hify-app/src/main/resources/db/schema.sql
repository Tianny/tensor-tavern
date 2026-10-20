-- ============================================================================
-- Hify 建表脚本（MySQL 8.x）
-- 用法：docker exec -i my-mysql mysql -uroot < hify-app/src/main/resources/db/schema.sql
--       （或本地 mysql 客户端：mysql -h127.0.0.1 -uroot < schema.sql）
-- 约定：主键 id bigint 自增；created_at/updated_at datetime；deleted 逻辑删除；
--       索引命名 idx_{表名}_{字段名}；外键关系在应用层维护，不建数据库级约束。
-- 脚本面向全新初始化，重复执行会在已存在的表上报错（此时应走变更脚本，不重跑本文件）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS hify DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE hify;

-- ----------------------------------------------------------------------------
-- provider：模型提供商（OpenAI / Claude / Gemini / Ollama）
-- ----------------------------------------------------------------------------
CREATE TABLE provider (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name             VARCHAR(64)  NOT NULL COMMENT '提供商名称，如 OpenAI',
  type             VARCHAR(32)  NOT NULL COMMENT '提供商类型：openai / openai_compatible / claude / gemini / ollama',
  base_url         VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'API 地址（含版本前缀，adapter 只追加具体端点），空串表示用官方默认地址',
  api_key          VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'API 密钥（本地部署可为空；将来多凭证类型存 JSON）',
  enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0=停用 1=启用',
  remark           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '备注',
  last_test_at     DATETIME     NULL DEFAULT NULL COMMENT '最近一次连通性测试时间，NULL=从未测试',
  last_test_ok     TINYINT      NULL DEFAULT NULL COMMENT '最近一次连通性测试结果：0=失败 1=成功，NULL=从未测试',
  last_test_message VARCHAR(255) NULL DEFAULT NULL COMMENT '最近一次连通性测试信息（失败原因等）',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_provider_type (type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模型提供商';

-- ----------------------------------------------------------------------------
-- model_config：模型配置（属于某个提供商）
-- ----------------------------------------------------------------------------
CREATE TABLE model_config (
  id                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  provider_id        BIGINT      NOT NULL COMMENT '所属提供商 provider.id',
  model_name         VARCHAR(64) NOT NULL COMMENT '模型标识，如 gpt-4o / claude-sonnet-4 / text-embedding-3-small',
  display_name       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '展示名称',
  model_type         VARCHAR(16) NOT NULL DEFAULT 'chat' COMMENT '模型类型：chat（对话）/ embedding（向量化）',
  max_context_tokens INT         NOT NULL DEFAULT 0 COMMENT '上下文窗口 token 上限，0=未知/不限',
  enabled            TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用：0=停用 1=启用',
  created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted            TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_model_config_provider_id (provider_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模型配置';

-- ----------------------------------------------------------------------------
-- mcp_server：MCP 工具服务（Agent 可调用的外部工具）
-- ----------------------------------------------------------------------------
CREATE TABLE mcp_server (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(64)  NOT NULL COMMENT '服务名称',
  description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '服务描述',
  transport   VARCHAR(16)  NOT NULL DEFAULT 'http' COMMENT '接入方式：http（含 SSE / Streamable HTTP）/ stdio',
  url         VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'HTTP 接入时的服务地址',
  command     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'stdio 接入时的启动命令',
  args        TEXT         NULL COMMENT 'stdio 启动参数，JSON 数组，如 ["--port","8080"]',
  headers     TEXT         NULL COMMENT 'HTTP 请求头，JSON 对象，如 {"Authorization":"Bearer xxx"}',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0=停用 1=启用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'MCP 工具服务';

-- ----------------------------------------------------------------------------
-- agent：Agent 配置（绑模型、绑工具、System Prompt）
-- ----------------------------------------------------------------------------
CREATE TABLE agent (
  id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  name            VARCHAR(64) NOT NULL COMMENT 'Agent 名称',
  description     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'Agent 描述',
  model_config_id BIGINT      NOT NULL COMMENT '绑定的模型配置 model_config.id',
  system_prompt   TEXT        NULL COMMENT 'System Prompt',
  temperature     DECIMAL(3,2) NOT NULL DEFAULT 0.70 COMMENT '采样温度 0.00-2.00',
  max_tokens      INT         NOT NULL DEFAULT 0 COMMENT '单次回复 token 上限，0=用模型默认',
  enabled         TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用：0=停用 1=启用',
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_agent_model_config_id (model_config_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 配置';

-- ----------------------------------------------------------------------------
-- agent_tool：Agent 与 MCP 工具的绑定关系（M:N）
-- ----------------------------------------------------------------------------
CREATE TABLE agent_tool (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  agent_id      BIGINT       NOT NULL COMMENT 'agent.id',
  mcp_server_id BIGINT       NOT NULL COMMENT 'mcp_server.id',
  tool_name     VARCHAR(128) NULL COMMENT '绑定的具体工具名；NULL 表示绑定该服务全部工具',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_agent_tool_agent_id (agent_id),
  KEY idx_agent_tool_mcp_server_id (mcp_server_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 与 MCP 工具绑定关系';

-- ----------------------------------------------------------------------------
-- chat_session：对话会话（属于某个 Agent）
-- 当前不做用户/权限体系，会话归属暂不落库；需要时再加 created_by 字段
-- Redis 上下文 key = session:{id}，其中 {id} 即本表主键
-- ----------------------------------------------------------------------------
CREATE TABLE chat_session (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，即 sessionId',
  agent_id    BIGINT       NULL COMMENT '关联的 agent.id，工作流会话为 NULL',
  workflow_id BIGINT       NULL COMMENT '关联的 workflow.id，Agent 会话为 NULL',
  title       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '会话标题',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_chat_session_agent_id (agent_id),
  KEY idx_chat_session_workflow_id (workflow_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '对话会话';

-- ----------------------------------------------------------------------------
-- chat_message：对话消息（增长最快的表，游标分页依赖 idx_chat_message_session_id）
-- ----------------------------------------------------------------------------
CREATE TABLE chat_message (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，游标分页的游标',
  session_id        BIGINT       NOT NULL COMMENT '所属会话 chat_session.id',
  role              VARCHAR(16)  NOT NULL COMMENT '角色：user / assistant / system / tool',
  content           TEXT         NOT NULL COMMENT '消息内容',
  prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '输入 token 数',
  completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 token 数',
  latency_ms        INT          NOT NULL DEFAULT 0 COMMENT 'LLM 响应耗时（毫秒）',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_chat_message_session_id (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '对话消息';

-- ----------------------------------------------------------------------------
-- workflow：工作流定义（顺序执行 + 条件分支，不做可视化拖拽）
-- ----------------------------------------------------------------------------
CREATE TABLE workflow (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(64)  NOT NULL COMMENT '工作流名称',
  description VARCHAR(255) NOT NULL DEFAULT '' COMMENT '工作流描述',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0=停用 1=启用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流定义';

-- ----------------------------------------------------------------------------
-- workflow_node：工作流节点（node_key 在工作流内唯一，连线用它引用）
-- ----------------------------------------------------------------------------
CREATE TABLE workflow_node (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  workflow_id BIGINT       NOT NULL COMMENT '所属工作流 workflow.id',
  node_key    VARCHAR(64)  NOT NULL COMMENT '节点标识，工作流内唯一，连线按此引用',
  node_type   VARCHAR(32)  NOT NULL COMMENT '节点类型：start / llm / condition / tool / end',
  name        VARCHAR(64)  NOT NULL COMMENT '节点名称',
  config      TEXT         NULL COMMENT '节点配置，JSON：LLM 节点含 model_config_id 与 prompt 模板等',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_workflow_node_workflow_id (workflow_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流节点';

-- ----------------------------------------------------------------------------
-- workflow_edge：工作流连线（含条件分支的表达式）
-- ----------------------------------------------------------------------------
CREATE TABLE workflow_edge (
  id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  workflow_id      BIGINT      NOT NULL COMMENT '所属工作流 workflow.id',
  source_node_key  VARCHAR(64) NOT NULL COMMENT '起点节点 workflow_node.node_key',
  target_node_key  VARCHAR(64) NOT NULL COMMENT '终点节点 workflow_node.node_key',
  condition_type   VARCHAR(16) NOT NULL DEFAULT 'always' COMMENT '分支类型：always（无条件）/ expression（条件满足）',
  condition_expr   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '条件表达式，condition_type=expression 时有效，如 ${node1.output} == "yes"',
  created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_workflow_edge_workflow_id (workflow_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流连线';

-- ----------------------------------------------------------------------------
-- knowledge_base：知识库（绑定向量化模型）
-- ----------------------------------------------------------------------------
CREATE TABLE knowledge_base (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name               VARCHAR(64)  NOT NULL COMMENT '知识库名称',
  description        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '知识库描述',
  embedding_model_id BIGINT       NOT NULL COMMENT '向量化模型 model_config.id（model_type=embedding）',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库';

-- ----------------------------------------------------------------------------
-- document：知识库文档
-- ----------------------------------------------------------------------------
CREATE TABLE document (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  knowledge_base_id BIGINT       NOT NULL COMMENT '所属知识库 knowledge_base.id',
  file_name         VARCHAR(255) NOT NULL COMMENT '文件名',
  file_type         VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '文件类型：pdf / txt / md / docx',
  file_size         BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
  status            VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '处理状态：pending / processing / completed / failed',
  error_msg         VARCHAR(512) NOT NULL DEFAULT '' COMMENT '处理失败原因',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_document_knowledge_base_id (knowledge_base_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档';

-- ----------------------------------------------------------------------------
-- agent_knowledge：Agent 与知识库绑定（M:N，应用层维护，无外键）
-- document_chunk 不在 MySQL：分块全文 + 向量统一存 pgvector（PG 表 document_chunk，
-- 由 hify-knowledge 的 PgVectorStore 启动时自动建表）
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- workflow_run：工作流运行记录（异步执行，前端拿 runId 轮询）
-- ----------------------------------------------------------------------------
CREATE TABLE workflow_run (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  workflow_id BIGINT       NOT NULL COMMENT '运行的 workflow.id',
  status      VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT '状态：running / succeeded / failed',
  inputs      TEXT         NULL COMMENT '运行输入 JSON（start 变量名 → 值）',
  output      MEDIUMTEXT   NULL COMMENT '最终输出（end 节点 outputTemplate 渲染结果）',
  error_msg   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '失败原因',
  node_traces MEDIUMTEXT   NULL COMMENT '节点执行轨迹 JSON 数组（NodeTrace）',
  latency_ms  INT          NOT NULL DEFAULT 0 COMMENT '总耗时（毫秒）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
  PRIMARY KEY (id),
  KEY idx_workflow_run_workflow_id (workflow_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流运行记录';
