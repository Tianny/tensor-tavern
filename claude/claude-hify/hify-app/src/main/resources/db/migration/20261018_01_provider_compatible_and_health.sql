-- 变更脚本：provider 表
-- 1. type 新增 openai_compatible（OpenAI 兼容端点：DeepSeek / Qwen / GLM / Kimi / vLLM / 中转等）
-- 2. 新增最近一次连通性测试结果（列表页健康展示，避免每次列表加载都真实探测）
-- 注意：schema.sql 已同步为最新结构，新环境直接执行 schema.sql 即可
ALTER TABLE provider
  MODIFY COLUMN type VARCHAR(32) NOT NULL
    COMMENT '提供商类型：openai / openai_compatible / claude / gemini / ollama',
  ADD COLUMN last_test_at DATETIME NULL DEFAULT NULL
    COMMENT '最近一次连通性测试时间，NULL=从未测试' AFTER remark,
  ADD COLUMN last_test_ok TINYINT NULL DEFAULT NULL
    COMMENT '最近一次连通性测试结果：0=失败 1=成功，NULL=从未测试' AFTER last_test_at,
  ADD COLUMN last_test_message VARCHAR(255) NULL DEFAULT NULL
    COMMENT '最近一次连通性测试信息（失败原因等）' AFTER last_test_ok;
