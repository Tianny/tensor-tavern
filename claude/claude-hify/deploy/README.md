# Hify 部署指南（Docker Compose 单机）

适用：几人～几十人规模的团队内部部署。一台装有 Docker（含 compose 插件）的服务器即可。

## 架构

```
浏览器 → hify-web (nginx, 对外端口 80)
            │  静态文件 + /api 反代（SSE 已关缓冲）
            ↓
         hify-app (Spring Boot :8080, 容器内部)
            ↓
   hify-mysql(8.4) / hify-redis(8) / hify-pg(pgvector:pg17)
            ↓
         外部 LLM API（openai / claude / gemini / ollama）
```

## 快速开始

```bash
cd deploy
cp .env.example .env
vi .env          # 修改三个密码（MYSQL/REDIS/PGVECTOR_PASSWORD）
docker compose up -d --build
```

首次启动约 3～5 分钟（Maven 与 npm 构建都在镜像内完成）。就绪判断：

```bash
docker compose ps                 # 5 个容器均 healthy
curl http://localhost/api/v1/health
```

然后浏览器打开 `http://<服务器IP>/`，先到「模型提供商」页添加 Provider（API Key 等），再建模型、Agent 即可使用。

## 数据持久化

| 卷 | 内容 | 说明 |
|---|---|---|
| `hify_mysql-data` | 全部业务数据 | **删除卷 = 数据清空** |
| `hify_pg-data` | 向量数据 | 重建后可从前端「重新向量化」恢复 |
| `hify_app-data` | 知识库上传的原始文件 | 同上 |
| `hify_redis-data` | 缓存 / 对话上下文 | 丢失无影响（TTL 2h 的缓存） |

## 建表机制

- **MySQL**：仅数据卷为空的首次启动时，自动执行 `db/schema.sql` 全量建表（日志见 `docker logs hify-mysql`）。已初始化过的卷不会重跑。
- **pgvector**：应用启动时幂等自建 `document_chunk` 表和索引，无需脚本。

## 常用操作

```bash
# 看后端日志（容器默认 prod profile：JSON 行式 stdout，含 traceId 链路）
docker logs -f hify-app

# 升级版本（拉新代码后重建）
docker compose up -d --build

# 停止 / 启动（数据保留）
docker compose stop && docker compose start

# 彻底下线（保留数据卷）
docker compose down

# 下线并清空全部数据（危险）
docker compose down -v
```

## 离线 / 内网交付

目标环境无外网时，在有网机器上构建后导出镜像包：

```bash
# 有网机器
# 注意：镜像架构需与目标服务器 CPU 一致。在 Apple Silicon 上构建给 x86 服务器用：
# DOCKER_DEFAULT_PLATFORM=linux/amd64 docker compose build
docker compose build
docker save hify-app hify-web mysql:8.4 redis:8-alpine pgvector/pgvector:pg17 nginx:1.27-alpine \
  | gzip > hify-images.tar.gz
# 连同 deploy/ 目录（去掉 .env）和 hify-images.tar.gz 一起拷到目标机器
# 目标机器
gunzip -c hify-images.tar.gz | docker load
cp .env.example .env && vi .env
docker compose up -d        # 不加 --build
```

内网访问外部 LLM API 需配代理，在 `.env` 里给 `JAVA_OPTS` 追加 `-Dhttps.proxyHost/-Dhttps.proxyPort`（Java 不认 `HTTP_PROXY` 环境变量）。

## 监控（Prometheus）

指标经 `http://<服务器IP>/actuator/prometheus` 暴露（nginx 已反代 `/actuator/`），全部 `hify_` 前缀：

| 指标 | 标签 | 说明 |
|---|---|---|
| `hify_chat_requests_total` | agent, outcome | 对话请求计数（Agent 会话按 agentId 分组，工作流会话为 workflow） |
| `hify_chat_request_duration_seconds_bucket` | - | 对话请求延迟直方图 |
| `hify_llm_calls_total` | provider, model, outcome | LLM 调用计数（outcome: success/failure/aborted，重试不重复计数） |
| `hify_llm_call_duration_seconds_bucket` | provider, model | LLM 调用延迟直方图 |
| `hify_circuit_breaker_state` | breaker, state | 熔断器状态（当前态 1 其余 0，告警盯 state="OPEN"） |
| `hify_mcp_tool_calls_total` | server, tool, outcome | MCP 工具调用计数 |

Prometheus 抓取配置示例：`job_name: hify` → `targets: ['hify-web:80']`（或主机 IP）→ `metrics_path: /actuator/prometheus`。

## 排障

| 症状 | 排查 |
|---|---|
| `hify-app` 反复重启 | `docker logs hify-app`：MySQL/Redis/pg 未就绪或密码与 `.env` 不一致 |
| 前端打开空白 | `docker logs hify-web`；确认 `hify-web` 容器已 healthy |
| 对话不出字/一次性吐完 | nginx 已配 `proxy_buffering off`；若前面还有公司级代理，需同样配置 |
| 上传文档失败 | 文件超 5MB 业务上限或 12MB 请求上限 |
| 端口冲突 | `.env` 设 `WEB_PORT` 换端口 |

## 已初始化库的存量升级

`db/migration/` 下的变更脚本只针对**早期初始化过**的环境；compose 全新部署永远只需 schema.sql（首次启动自动执行），不要手动跑 migration。
