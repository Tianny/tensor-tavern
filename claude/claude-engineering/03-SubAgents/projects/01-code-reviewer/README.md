# 项目 2：代码审查器 (Code Reviewer)

## 场景说明

这是一个典型的"角色边界"场景。代码审查器：
- **只需要读取权限**：不需要修改代码
- **需要严格的安全边界**：防止误操作
- **需要专业的审查视角**：关注安全、性能、可维护性

## 子代理配置

查看 `.claude/agents/code-reviewer.md`

```yaml
name: code-reviewer
description: Review code changes for quality, security, and best practices
tools: Read, Grep, Glob, Bash
model: sonnet
```

### 配置解析

- **tools**: 只给 `Read/Grep/Glob`（只读），加 `Bash` 用于 `git diff`
- **model**: 使用 `sonnet`，需要更强的分析能力
- **无 Edit/Write**：**关键！** 审查者不应修改代码

## 如何使用

### 检查最近改动

```
让 code-reviewer 看一下我刚改的代码
```

### 指定文件审查

```
用 code-reviewer 审查 src/auth.js 的安全性
```

### 审查 PR/提交

```
让 code-reviewer 检查最近 3 次提交的代码质量
```

## 项目结构

```
02-code-reviewer/
├── src/
│   ├── auth.js        # 故意包含安全问题
│   ├── database.js    # 故意包含 SQL 注入风险
│   └── api.js         # 故意包含不良实践
└── .claude/agents/
    └── code-reviewer.md
```

## 学习要点

1. **最小权限原则**：审查者不需要写权限
2. **专业化 Prompt**：明确审查维度（安全/性能/可维护性）
3. **模型选择**：需要分析能力时用 sonnet

## 示例代码说明

本项目的代码**故意包含安全问题**，用于演示审查器的发现能力：

| 文件 | 问题类型 |
|------|----------|
| auth.js | 硬编码密钥、弱加密、信息泄露 |
| database.js | SQL 注入、明文密码 |
| api.js | 无速率限制、敏感数据暴露 |
