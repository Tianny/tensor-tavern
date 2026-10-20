-- 变更脚本：workflow 模块落地（简版编排：顺序执行 + 条件分支）
-- workflow / workflow_node / workflow_edge 三表随初始 schema 已存在，无需变更
-- 本脚本新增 workflow_run：运行记录（状态 / 输入输出 / 节点轨迹 / 耗时）
-- 注意：schema.sql 已同步为最新结构，新环境直接执行 schema.sql 即可
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
