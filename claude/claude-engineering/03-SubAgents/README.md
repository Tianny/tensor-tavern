## 什么是子代理（Subagent）

一句话：

子代理 = 一个“专职小助手”，带着自己的规则（system prompt）、自己的工具权限、自己的上下文窗口，去完成某一类任务，然后把“结果摘要”带回来。

它和主对话的区别就三点：

隔离上下文：子代理干活的探索过程、冗长输出不污染主对话

强约束：你能规定它“只能读文件/只能跑测试/不能改代码”

可复用：做成文件后（项目级/用户级）下次直接用，像工程资产

你可以把它理解成：
把“一个大脑”拆成多个“岗位角色”，每个岗位只做一件事，并且有权限边界。

## 子代理极简上手（最短路径）

A. 用交互命令创建（推荐）

在 Claude Code 输入：/agents

Create new agent → 选 User-level 或 Project-level

Generate with Claude → 用一句话描述它要干什么（越清晰越容易被自动调用）

选择工具（Read-only / Bash / Edit / Write…）

选模型（haiku/sonnet/opus/inherit）

保存即可（立刻可用）

B. 手写一个最小可用 subagent 文件（项目级）

放到：.claude/agents/code-reviewer.md

---
name: code-reviewer
description: Proactively review code changes for quality and security after I modify code.
tools: Read, Grep, Glob, Bash
model: sonnet
---
You are a senior code reviewer.
When invoked:
1) run git diff
2) focus on changed files
3) report issues by priority: Critical / Warning / Suggestion
Return concise, actionable fixes.


然后在主对话里这样用：

“用 code-reviewer 子代理检查我刚改的东西”

“让 code-reviewer 看一下最近 diff，给出风险点”

上手关键：description 写清楚“何时触发”，prompt 写清楚“怎么做 + 输出格式”。

## 工程实战：什么时候适合用子代理（给你一组“可复用判断”）

最适合子代理的 4 类任务（高频刚需）

① 高噪声输出任务（不想污染主对话）

跑测试（输出巨多）

扫日志、扫报错栈

全项目 grep / ripgrep 搜索

生成大型报告但主对话只要结论

✅ 用法：test-runner、log-triager 子代理

② 明确角色边界的任务（强约束）

只允许读，不允许写（review / audit）

只允许 SELECT，不允许写库（db-reader）

只能改某个目录（例如只改 frontend/）

✅ 用法：code-reviewer（无 Edit/Write）、db-reader（hook 校验）

③ 可并行的研究任务（多线探索）

auth / db / api 三块同时摸清

三个候选方案同时比较（技术选型）

三套实现路径同时给优缺点

✅ 用法：多个子代理并行，主对话负责综合

④ 可流水线分段的任务（链式）

Explore（找位置）→ Reviewer（指出问题）→ Debugger（修）→ Test-runner（验）

✅ 用法：你在主对话里“串起来”，子代理一个个干

反过来：不适合子代理的 3 种情况

需要频繁来回问你（需求不断变）

多阶段共享同一上下文（从产品到实现到评估都强耦合）

就改一两行、很快的事（启动子代理反而慢）