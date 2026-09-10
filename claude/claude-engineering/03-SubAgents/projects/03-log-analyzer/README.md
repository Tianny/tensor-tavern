# 项目 3：日志分析器 (Log Analyzer)

## 场景说明

这是另一个典型的"高噪声输出"场景。日志文件通常：
- 非常冗长（几千到几百万行）
- 包含大量噪声（INFO 级别日志）
- 真正重要的信息被淹没

子代理可以帮你：**筛选、分类、总结日志中的关键问题**

## 子代理配置

查看 `.claude/agents/log-analyzer.md`

```yaml
name: log-analyzer
description: Analyze log files and extract actionable insights
tools: Read, Grep, Glob, Bash
model: sonnet
```

### 配置解析

- **tools**: 只读 + Bash（用于 grep/awk 等日志处理）
- **model**: `sonnet`，需要模式识别和归纳能力
- **无 Edit/Write**：分析者不需要修改文件

## 如何使用

### 分析错误日志

```
让 log-analyzer 分析 logs/ 目录下的错误，找出主要问题
```

### 查找特定问题

```
用 log-analyzer 找出所有超时相关的日志
```

### 时间范围分析

```
让 log-analyzer 分析今天 14:00-15:00 之间的异常
```

## 项目结构

```
03-log-analyzer/
├── logs/
│   ├── app.log         # 应用日志（混合各种级别）
│   ├── error.log       # 错误日志
│   └── access.log      # 访问日志
└── .claude/agents/
    └── log-analyzer.md
```

## 学习要点

1. **信息压缩**：从海量日志提取关键信息
2. **模式识别**：找出重复出现的错误模式
3. **时间线重建**：理解问题发生的时序

## 示例日志说明

日志文件包含多种场景：

| 日志类型 | 包含内容 |
|----------|----------|
| app.log | 混合 INFO/WARN/ERROR，多种组件 |
| error.log | 数据库超时、API 错误、认证失败 |
| access.log | HTTP 请求记录，包含慢请求和 4xx/5xx |
