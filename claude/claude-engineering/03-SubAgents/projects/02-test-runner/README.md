# 项目 1：测试运行器 (Test Runner)

## 场景说明

这是一个典型的"高噪声输出"场景。运行测试时会产生大量日志输出，但我们通常只关心：
- 有多少测试通过/失败
- 失败的测试是什么
- 错误原因是什么

## 子代理配置

查看 `.claude/agents/test-runner.md`

```yaml
name: test-runner
description: Run tests and report results concisely
tools: Read, Bash, Glob, Grep
model: haiku
```

### 配置解析

- **tools**: 需要 `Bash` 来执行测试命令，`Read/Glob/Grep` 用于定位测试文件
- **model**: 使用 `haiku`，因为任务相对简单，追求速度
- **description**: 明确说明"运行测试并简洁报告"

## 如何使用

### 方式 1：显式调用

```
让 test-runner 子代理跑一下所有测试
```

### 方式 2：Claude 自动调用

当你说类似这样的话时，Claude 会自动选择使用 test-runner：

```
帮我跑一下测试看看有没有问题
运行测试套件
检查一下测试是否都通过
```

## 项目结构

```
01-test-runner/
├── src/
│   ├── calculator.js      # 被测试的模块
│   └── calculator.test.js # 测试文件
├── package.json
└── .claude/agents/
    └── test-runner.md     # 子代理配置
```

## 学习要点

1. **隔离噪声**：测试输出不污染主对话
2. **结果摘要**：子代理只返回关键信息
3. **模型选择**：简单任务用 haiku 更高效
