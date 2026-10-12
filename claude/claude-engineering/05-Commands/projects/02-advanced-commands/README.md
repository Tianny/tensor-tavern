# 示例项目：高级斜杠命令

这个项目展示高级命令功能：Hooks、文件引用、预处理命令等。

## 包含的命令

```
.claude/commands/
├── safe-deploy.md      # 带 hooks 的部署命令
├── pr-create.md        # 多参数 + 预处理命令
└── analyze.md          # 文件引用 + 上下文注入
```

## 高级特性演示

### 1. safe-deploy - 带 Hooks 的命令

展示如何在命令中定义 hooks：
- `PreToolUse`: 执行前检查
- `PostToolUse`: 执行后自动运行测试
- `once: true`: 只触发一次

```
/safe-deploy staging
```

### 2. pr-create - 多参数 + 预处理

展示高级参数处理：
- 多位置参数：`$1`, `$2`
- 预处理命令：`` !`git branch --show-current` ``
- 条件逻辑

```
/pr-create "Add feature" "Detailed description here"
```

### 3. analyze - 文件引用 + 上下文

展示如何引入外部文件作为上下文：
- `@path/to/file` 语法
- 动态上下文注入

```
/analyze src/auth.js
```

## 学习要点

1. **Hooks 集成**：命令执行时自动触发检查/操作
2. **预处理命令**：`` !`command` `` 注入动态内容
3. **文件引用**：`@file` 包含外部文件内容
4. **多参数**：`$1`, `$2` 处理多个参数
5. **一次性 Hook**：`once: true` 避免重复触发
