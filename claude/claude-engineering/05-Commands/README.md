# Slash Commands 自定义命令

> 把重复的对话模式，变成一个 `/命令`

---

## 核心概念

斜杠命令 = **预设好的 Prompt 模板**。定义一次，`/命令名` 反复调用。

```
# 没有命令：每次都要解释
"帮我检查 git 状态，提交代码，消息是 fix login bug"

# 有了命令：直达目标
/commit fix login bug
```

---

## 命令存放位置

```
项目级（团队共享，git 追踪）
└── .claude/commands/your-command.md

用户级（个人使用）
└── ~/.claude/commands/your-command.md
```

---

## 创建命令

### 最简形式

`.claude/commands/hello.md`：

```markdown
Say hello and tell me a programming joke.
```

命令文件的内容就是发送给 Claude 的 prompt。

### 带参数

用 `$ARGUMENTS` 接收参数，或 `$1` `$2` 接收多个参数：

```markdown
Create a git commit with the following message: $ARGUMENTS
```

### Frontmatter 配置

```markdown
---
description: Quick git commit
argument-hint: [commit message]
allowed-tools: Bash(git status:*), Bash(git add:*), Bash(git commit:*)
model: haiku
---

Create a git commit with the message: $ARGUMENTS
```

| 字段 | 作用 |
|------|------|
| `description` | 显示在 `/help` 中的描述 |
| `argument-hint` | 参数提示 |
| `allowed-tools` | 预授权工具，免确认 |
| `model` | 指定模型 |

### 引用文件与预处理

```markdown
# 用 @ 引用文件内容
Review against our standards: @docs/coding-standards.md

# 用 !`cmd` 执行命令并嵌入输出
Recent commits:
!`git log --oneline -10`
```

---

## 命令中的 Hooks

```markdown
---
hooks:
  - event: PreToolUse
    matcher: Bash
    command: echo "About to run: $TOOL_INPUT"
  - event: PostToolUse
    matcher: Edit
    command: prettier --write "$FILE_PATH"
---
```

| 事件 | 触发时机 |
|------|----------|
| `PreToolUse` | 工具执行前 |
| `PostToolUse` | 工具执行后 |
| `Stop` | 命令完成时 |

加 `once: true` 可让 hook 只触发一次。

---

## 命名空间

目录结构自动形成命名空间：

```
.claude/commands/
├── git/
│   ├── commit.md      →  /git:commit
│   └── push.md        →  /git:push
└── test/
    ├── unit.md        →  /test:unit
    └── e2e.md         →  /test:e2e
```

---

## 命令 vs 子代理 vs Skills

| | 斜杠命令 | 子代理 | Skills |
|--|----------|--------|--------|
| **触发** | `/命令名` 手动 | 指定或自动 | Claude 自动判断 |
| **上下文** | 共享主对话 | 隔离执行 | 共享主对话 |
| **适合** | 简单重复操作 | 复杂高噪声任务 | 需要自动判断的能力 |

---

## 参考资源

- [Slash Commands 官方文档](https://code.claude.com/docs/en/slash-commands)
- [Claude Code 最佳实践](https://www.anthropic.com/engineering/claude-code-best-practices)
- [Awesome Claude Code 命令集合](https://github.com/wshobson/commands)
