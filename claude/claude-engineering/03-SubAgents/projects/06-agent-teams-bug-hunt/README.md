# 项目 05：全栈 Bug 猎人 — Agent Teams 竞争假设调试

## 项目概述

这是一个 **Agent Teams 工程化实战项目**，演示如何用 Claude Code 的多会话协作能力进行复杂系统的调试。

项目包含一个**刻意植入多个关联 bug 的 Express.js 应用**。这些 bug 之间存在因果级联关系——任何单独的调查者都很难发现完整的根因链。只有当多个调查者**共享发现、互相挑战**时，才能拼出完整的拼图。

这正是 Agent Teams 的核心价值：**当理解问题需要跨视角协作时，并行独立调查远不如团队辩论。**

## 场景设定

用户报告了三个看似独立的问题：

1. **会话丢失**：用户登录后偶尔被踢出
2. **性能退化**：高峰期 API 响应变慢
3. **数据泄漏**：极少数情况下用户看到其他人的订单

详细症状见 [bug-report.md](bug-report.md)。

## 真相（剧透警告）

> ⚠️ 如果你想先自己体验 Agent Teams 调试，请跳过这一节。

这三个症状的根因是**四个相互关联的 bug 形成的级联故障**：

```
Bug 1: DB 连接池太小（db.js:8）
    ↓ 连接耗尽
Bug 2: Session 存储的 Redis 客户端不处理重连（middleware/session.js:12-15）
    ↓ Session 写入失败被静默吞掉
Bug 3: 订单查询的 N+1 问题（routes/orders.js:15-22）
    ↓ 大量 DB 连接被占用 → 加剧 Bug 1
Bug 4: 缓存中间件的竞态条件（middleware/cache.js:18-35）
    ↓ 缓存失效窗口期返回错误用户的数据

级联链：Bug 1 → Bug 2（会话丢失）
         Bug 3 → Bug 1 → Bug 2（高峰期更严重）
         Bug 1 → Bug 4（缓存重建时竞态窗口增大 → 数据泄漏）
```

**关键洞察**：单独看每个 bug 都"不严重"，但它们的级联效应才是真正的问题。这就是为什么单个调查者容易遗漏——他找到一个 bug 后就"满意"了，不会继续挖掘。

## 如何运行

### 前提条件

- Claude Code 已安装
- 已启用 Agent Teams（见下方）

### 步骤 1：启用 Agent Teams

```bash
# 在 settings.json 中添加（推荐）
# 或设置环境变量
export CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1
```

### 步骤 2：启动 Claude Code

```bash
cd 03-SubAgents/projects/05-agent-teams-bug-hunt
claude
```

### 步骤 3：使用 team-prompt.md 中的指令

将 [team-prompt.md](team-prompt.md) 中的 prompt 粘贴给 Claude，它会：

1. 创建 Agent Team
2. 生成 4 个调查员 Teammates
3. 分配调查方向
4. 协调辩论和发现共享
5. 综合最终报告

### 步骤 4：观察和引导

- 用 `Shift+↑/↓` 在 Teammates 之间切换
- 用 `Ctrl+T` 查看共享任务列表
- 如果某个 Teammate 方向偏了，直接对话纠正

## 项目结构

```
05-agent-teams-bug-hunt/
├── README.md                    # 本文件
├── bug-report.md                # 用户报告的症状（调查起点）
├── team-prompt.md               # Agent Team 启动指令
├── findings-template.md         # 调查报告模板
├── buggy-app/                   # 有 bug 的应用代码
│   ├── package.json
│   ├── server.js                # Express 主服务
│   ├── routes/
│   │   ├── auth.js              # 认证路由
│   │   ├── users.js             # 用户路由
│   │   └── orders.js            # 订单路由（N+1 bug）
│   ├── middleware/
│   │   ├── session.js           # Session 中间件（Redis bug）
│   │   └── cache.js             # 缓存中间件（竞态 bug）
│   └── db.js                    # 数据库连接（连接池 bug）
└── hooks/
    ├── teammate-idle.sh         # TeammateIdle Hook 示例
    └── task-completed.sh        # TaskCompleted Hook 示例
```

## 学习目标

通过这个项目你将掌握：

1. **竞争假设模式**的实际应用
2. **Teammate 间通信**如何帮助发现关联问题
3. **Hooks 质量门禁**（TeammateIdle / TaskCompleted）的使用
4. **Delegate Mode** 让 Lead 专注协调
5. 如何为 Agent Team 设计**有效的调查 prompt**
