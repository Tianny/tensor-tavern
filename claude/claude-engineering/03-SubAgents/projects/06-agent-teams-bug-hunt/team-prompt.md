# Agent Team 启动指令

## 方式一：完整竞争假设模式（推荐）

将以下内容粘贴给 Claude：

```
阅读 bug-report.md 中描述的三个症状。然后创建一个 agent team 来调查这些问题。

生成 4 个 investigator teammates：
- "Session 侦探"：假设根因在 Session/Redis 层。重点审查 middleware/session.js 和 server.js 中的 session 配置。
- "数据库侦探"：假设根因在数据库连接和查询层。重点审查 db.js 和 routes/ 下所有路由的数据库操作。
- "缓存侦探"：假设根因在缓存机制。重点审查 middleware/cache.js 以及缓存与用户隔离相关的逻辑。
- "架构侦探"：不预设假设，从整体架构角度分析各组件的交互。重点关注错误处理、资源管理和并发安全。

每个 teammate 的 prompt 中包含：
1. buggy-app/ 目录包含完整的应用代码
2. 他们需要用 Read/Grep/Glob 工具审查代码
3. 找到可疑问题后，要发消息告诉其他 teammates
4. 如果其他 teammate 的发现与自己的发现有关联，要主动指出
5. 特别注意：三个症状可能不是独立的，要寻找它们之间的因果关系

要求所有 teammates 在完成初步调查后互相分享发现，并尝试挑战彼此的结论。

最终综合所有发现，生成一份按照 findings-template.md 格式的调查报告。
```

## 方式二：带 Delegate Mode 和 Hooks

```
阅读 bug-report.md。创建 agent team 调查这些 bug。

生成 4 个 teammates：Session 侦探、数据库侦探、缓存侦探、架构侦探。
每个 teammate 负责审查 buggy-app/ 中对应层面的代码。
要求它们找到问题后互相通信，寻找 bug 之间的关联。

我会进入 Delegate Mode，你只负责协调，不要自己分析代码。
每个 teammate 完成初步发现后，安排一轮"辩论"让它们互相挑战。
最终综合成按 findings-template.md 格式的报告。
```

启动后按 `Shift+Tab` 进入 Delegate Mode。

## 方式三：带 Plan Approval

```
阅读 bug-report.md。创建 agent team 调查这些 bug。

生成 4 个 investigator teammates，每个负责不同的调查方向。
要求每个 teammate 在开始深入代码审查前，先提交调查计划等待审批。
只批准包含具体文件列表和假设的计划。
拒绝过于笼统的计划（如"我会看看所有代码"）。

teammates 完成调查后要互相分享发现并辩论。
```

## 观察要点

运行团队后，注意观察：

1. **初始阶段**：每个 Teammate 是否按照自己的假设方向调查？
2. **发现阶段**：Teammate 发现 bug 后是否主动通知其他 Teammates？
3. **关联阶段**：是否有 Teammate 把不同发现联系起来（"你的 Redis 问题可能和我的连接池问题有关"）？
4. **辩论阶段**：Lead 是否组织了发现共享和挑战？
5. **收敛阶段**：最终报告是否识别出了 bug 之间的级联关系？
