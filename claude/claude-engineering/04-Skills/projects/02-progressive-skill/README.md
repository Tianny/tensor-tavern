# 示例项目：渐进式披露 Skill

这个项目展示完整的 Skill 结构，演示渐进式披露（Progressive Disclosure）模式。

## 目录结构

```
02-progressive-skill/
├── SKILL.md              # 主指令文件（入口点）
├── PATTERNS.md           # 框架路由模式参考
├── STANDARDS.md          # 文档标准规范
├── EXAMPLES.md           # 使用示例
├── templates/            # 文档模板
│   ├── endpoint.md       # 单个端点模板
│   ├── index.md          # API 索引模板
│   └── openapi.yaml      # OpenAPI 规范模板
└── scripts/              # 辅助脚本
    ├── detect_routes.py  # 自动检测路由
    └── validate_openapi.sh # 验证 OpenAPI 规范
```

## Skill 说明

这是一个 API 文档生成 Skill。当用户需要为代码生成 API 文档时，Claude 会：

1. 首先加载 `SKILL.md`（主指令）
2. 根据需要加载其他文件（如模式、标准、模板）
3. 可以执行脚本来辅助工作

## 渐进式披露演示

### 场景 1：简单请求
用户："帮我生成 API 文档"

Claude 加载：
- `SKILL.md` ✓
- 其他文件暂不加载

### 场景 2：需要模式参考
用户："这是 Express.js 项目，帮我提取路由"

Claude 加载：
- `SKILL.md` ✓
- `PATTERNS.md` ✓（因为涉及框架特定模式）

### 场景 3：需要生成 OpenAPI
用户："生成 OpenAPI 规范"

Claude 加载：
- `SKILL.md` ✓
- `templates/openapi.yaml` ✓（需要模板结构）
- `STANDARDS.md` ✓（确保符合标准）

### Token 消耗对比

| 场景 | 传统方式 | 渐进式披露 |
|------|----------|-----------|
| 简单请求 | ~3000 tokens | ~800 tokens |
| 框架特定 | ~3000 tokens | ~1200 tokens |
| 完整生成 | ~3000 tokens | ~2000 tokens |

## 关键学习点

### 1. SKILL.md 保持简洁

主文件只包含：
- 高层流程
- 文件引用（告诉 Claude 去哪找详细信息）
- 基本指令

详细内容放在引用文件中。

### 2. 有意义的文件名

```
PATTERNS.md     # 清楚表示"模式"
STANDARDS.md    # 清楚表示"标准"
EXAMPLES.md     # 清楚表示"示例"
```

Claude 通过文件名快速判断需要哪个文件。

### 3. 脚本封装复杂逻辑

```bash
# 不需要 Claude 理解每行代码
python scripts/detect_routes.py <directory>
```

Claude 可以执行脚本而不需要将整个脚本内容放入上下文。

### 4. 模板提供一致性

`templates/` 目录包含标准化模板，确保输出格式一致。

## 测试方法

尝试以下请求观察 Claude 的行为：

1. "帮我为 src/api 目录生成 API 文档"
2. "这是 FastAPI 项目，提取所有路由"
3. "生成 OpenAPI 3.0 规范"
4. "按照团队标准格式化这个端点文档"

观察 Claude 会加载哪些文件。

## 扩展建议

可以添加更多内容：
- `reference/` 目录：详细的 API 参考
- `validation/` 目录：验证规则
- 更多框架支持的 pattern 文件
