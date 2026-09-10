# 项目：React 电商前端

## 技术栈
- React 18 + TypeScript
- Vite 构建工具
- TanStack Query (数据获取)
- Zustand (状态管理)
- Tailwind CSS (样式)
- React Router v6 (路由)

## 目录结构
```
src/
├── components/      # 可复用组件
│   ├── ui/         # 基础 UI 组件
│   └── features/   # 功能组件
├── pages/          # 页面组件
├── hooks/          # 自定义 Hooks
├── stores/         # Zustand stores
├── api/            # API 调用
├── types/          # TypeScript 类型
└── utils/          # 工具函数
```

## 编码规范

### 组件规范
- 使用函数组件 + Hooks
- Props 使用 interface 定义，命名为 `ComponentNameProps`
- 组件文件使用 PascalCase：`ProductCard.tsx`
- 每个组件一个目录，包含 index.tsx 和样式

### 状态管理
- 全局状态使用 Zustand
- 服务器状态使用 TanStack Query
- 本地状态使用 useState/useReducer

### 样式规范
- 使用 Tailwind 工具类
- 复杂样式抽取为组件
- 响应式使用 sm/md/lg/xl 断点

## 常用命令
```bash
pnpm dev          # 启动开发服务器
pnpm build        # 构建生产版本
pnpm test         # 运行测试
pnpm lint         # 代码检查
pnpm preview      # 预览构建结果
```

## API 集成
- 基础 URL: `import.meta.env.VITE_API_URL`
- 使用 axios 实例，配置在 `src/api/client.ts`
- 所有 API 调用封装在 `src/api/` 目录

## Git 规范
- Commit: `type(scope): message`
- 分支: feature/*, bugfix/*, hotfix/*
- PR 必须通过 CI 检查
