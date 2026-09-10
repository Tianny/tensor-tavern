# 项目：订单服务 API

## 概述
订单微服务，处理订单创建、支付、发货等业务逻辑。

## 技术栈
- Node.js 20 + TypeScript
- Fastify (Web 框架)
- Prisma (ORM)
- PostgreSQL (主数据库)
- Redis (缓存 + 消息队列)
- Zod (数据验证)

## 目录结构
```
src/
├── routes/         # 路由定义
├── controllers/    # 请求处理
├── services/       # 业务逻辑
├── repositories/   # 数据访问
├── schemas/        # Zod schemas
├── middlewares/    # 中间件
├── utils/          # 工具函数
└── types/          # 类型定义

prisma/
├── schema.prisma   # 数据库模型
└── migrations/     # 迁移文件
```

## API 规范

### 响应格式
```typescript
interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    code: string;
    message: string;
  };
  meta?: {
    page: number;
    limit: number;
    total: number;
  };
}
```

### 错误码
- `ORDER_NOT_FOUND` - 订单不存在
- `INVALID_STATUS` - 状态转换无效
- `PAYMENT_FAILED` - 支付失败
- `STOCK_INSUFFICIENT` - 库存不足

### 端点命名
```
GET    /api/v1/orders          # 订单列表
GET    /api/v1/orders/:id      # 订单详情
POST   /api/v1/orders          # 创建订单
PUT    /api/v1/orders/:id      # 更新订单
DELETE /api/v1/orders/:id      # 取消订单
POST   /api/v1/orders/:id/pay  # 支付订单
```

## 数据库规范
- 表名使用 snake_case 复数形式
- 主键统一使用 UUID
- 必须有 created_at, updated_at 字段
- 软删除使用 deleted_at 字段

## 认证
- JWT Bearer Token
- Token 通过 Authorization header 传递
- 公开端点在路由中标记 `{ auth: false }`

## 常用命令
```bash
pnpm dev              # 启动开发服务器
pnpm build            # 构建
pnpm start            # 生产启动
pnpm test             # 运行测试
pnpm prisma migrate   # 运行迁移
pnpm prisma generate  # 生成 Prisma Client
```

## 环境变量
见 `.env.example`，本地配置放在 `.env.local`
