# 示例项目：基础斜杠命令

这个项目展示如何创建和使用自定义斜杠命令。

## 包含的命令

```
.claude/commands/
├── commit.md           # /commit - Git 快速提交
├── review.md           # /review - 代码审查
├── explain.md          # /explain - 代码解释
├── todo.md             # /todo - 添加 TODO
└── git/                # 命名空间示例
    ├── status.md       # /git:status
    └── log.md          # /git:log
```

## 命令详解

### /commit - 快速提交

最常用的命令之一。分析变更，自动生成提交信息。

```
/commit                    # 自动生成消息
/commit fix login bug      # 使用指定消息
```

**特点**：使用 `allowed-tools` 预授权 git 命令

### /review - 代码审查

审查指定文件或当前变更。

```
/review                    # 审查当前变更
/review src/auth.js        # 审查指定文件
```

**特点**：定义了输出格式模板

### /explain - 代码解释

用简单语言解释代码。

```
/explain 这个递归函数
/explain src/utils.js
```

**特点**：使用 `argument-hint` 提示用户

### /todo - 添加 TODO

快速在代码中添加 TODO 注释。

```
/todo 处理边界条件
/todo ! 紧急：修复安全漏洞
```

**特点**：支持优先级标记

### /git:status 和 /git:log - 命名空间示例

展示如何使用目录组织相关命令。

```
/git:status    # 查看状态
/git:log       # 查看日志
```

## 如何使用

1. 将 `.claude/commands/` 目录复制到你的项目
2. 运行 `/help` 查看所有可用命令
3. 直接使用命令，如 `/commit`, `/review`

## 学习要点

1. **基础语法**：Markdown 文件 = prompt 模板
2. **参数传递**：`$ARGUMENTS` 和 `$1`, `$2`
3. **元数据**：YAML frontmatter 配置
4. **预授权**：`allowed-tools` 跳过确认
5. **命名空间**：目录结构组织命令
