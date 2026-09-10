# 调查报告 — ShopStream 电商平台

## 调查摘要

**调查日期**：2026-02-10
**调查团队**：Bug Hunt Agent Team（4 名侦探并行调查）
- Session 侦探（session-detective）：Session/Redis 层
- 数据库侦探（db-detective）：数据库连接与查询层
- 缓存侦探（cache-detective）：缓存机制
- 架构侦探（arch-detective）：整体架构与组件交互

**调查对象**：ShopStream 电商平台 — 会话丢失/性能退化/数据泄漏

---

## 发现的 Bug 列表

### Bug #1: 缓存 Key 缺少用户标识（数据泄漏根因）
- **文件**：`buggy-app/middleware/cache.js:22`
- **严重性**：P0
- **类型**：安全
- **发现者**：全部 4 名侦探独立发现
- **描述**：缓存 key 仅使用 URL 路径（`${prefix}:${req.originalUrl}`），不包含任何用户标识。`/api/orders` 对所有用户共享同一缓存条目。用户 A 的订单数据被缓存后，用户 B 访问同一 URL 直接命中缓存，看到用户 A 的订单。
- **代码片段**：
```javascript
// cache.js:22 — 缓存 key 只有 URL，没有 userId
const cacheKey = `${prefix}:${req.originalUrl}`;
```
- **建议修复**：
```javascript
// 缓存 key 必须包含用户标识
const cacheKey = `${prefix}:${req.userId}:${req.originalUrl}`;
```

---

### Bug #2: 数据库连接池配置严重不足
- **文件**：`buggy-app/db.js:12-16`
- **严重性**：P1
- **类型**：性能
- **发现者**：全部 4 名侦探独立发现
- **描述**：连接池 `max: 5`，生产环境 50+ 并发用户仅有 5 个连接。`idleTimeoutMillis: 5000` 导致连接频繁回收重建。`connectionTimeoutMillis: 3000` 高峰期排队容易超时。
- **代码片段**：
```javascript
// db.js:12-16
max: 5,                        // 50+ 用户只有 5 个连接
idleTimeoutMillis: 5000,       // 空闲 5s 就回收，频繁重建
connectionTimeoutMillis: 3000, // 排队 3s 超时
```
- **建议修复**：
```javascript
max: 20,                        // 根据并发量调整
idleTimeoutMillis: 30000,       // 30s 空闲超时
connectionTimeoutMillis: 10000, // 10s 排队超时
```

---

### Bug #3: N+1 查询严重消耗连接池
- **文件**：`buggy-app/routes/orders.js:30-38`
- **严重性**：P1
- **类型**：性能
- **发现者**：全部 4 名侦探独立发现
- **描述**：`GET /api/orders` 对每个订单单独执行查询获取订单项。用户有 50 个订单 = 51 次数据库查询。每次 `pool.query()` 都从连接池获取连接，与 Bug #2 叠加后高峰期迅速耗尽连接池。
- **代码片段**：
```javascript
// orders.js:30-38 — 对每个订单循环查询
for (const order of orders) {
  const itemsResult = await query(
    'SELECT oi.id, oi.quantity, oi.price, p.name as product_name ' +
    'FROM order_items oi JOIN products p ON p.id = oi.product_id ' +
    'WHERE oi.order_id = $1',
    [order.id]
  );
  order.items = itemsResult.rows;
}
```
- **建议修复**：
```javascript
// 一次性批量查询所有订单项
const orderIds = orders.map(o => o.id);
const itemsResult = await query(
  'SELECT oi.order_id, oi.id, oi.quantity, oi.price, p.name as product_name ' +
  'FROM order_items oi JOIN products p ON p.id = oi.product_id ' +
  'WHERE oi.order_id = ANY($1)',
  [orderIds]
);
// 按 order_id 分组
const itemsByOrder = {};
itemsResult.rows.forEach(item => {
  if (!itemsByOrder[item.order_id]) itemsByOrder[item.order_id] = [];
  itemsByOrder[item.order_id].push(item);
});
orders.forEach(order => { order.items = itemsByOrder[order.id] || []; });
```

---

### Bug #4: Redis 连接无重连逻辑
- **文件**：`buggy-app/middleware/session.js:14-17`
- **严重性**：P1
- **类型**：可靠性
- **发现者**：Session 侦探、架构侦探
- **描述**：Redis 错误处理仅 `console.error`，无重连机制、无 fallback、无告警。Redis 断连后所有 session 读写静默失败，用户持有有效 JWT 但 session 数据丢失，被踢回登录页。
- **代码片段**：
```javascript
// session.js:14-17 — 只打日志，不重连
redisClient.on('error', (err) => {
  console.error('Redis session error:', err.message);
  // 没有重连！没有 fallback！没有告警！
});
```
- **建议修复**：
```javascript
// 配置自动重连策略
const redisClient = createClient({
  url: process.env.REDIS_URL || 'redis://localhost:6379',
  socket: {
    reconnectStrategy: (retries) => Math.min(retries * 100, 5000),
  },
});
```

---

### Bug #5: Redis 连接无就绪检查（Fire-and-Forget）
- **文件**：`buggy-app/middleware/session.js:20`
- **严重性**：P2
- **类型**：可靠性
- **发现者**：Session 侦探、架构侦探
- **描述**：`redisClient.connect().catch(console.error)` 是 fire-and-forget。应用不等待 Redis 连接成功就开始接受请求，启动初期的 session 操作静默失败。
- **代码片段**：
```javascript
// session.js:20 — 不等待连接完成
redisClient.connect().catch(console.error);
```
- **建议修复**：
```javascript
// 在 server.js 中等待 Redis 就绪后再启动服务
await redisClient.connect();
app.listen(PORT, () => { ... });
```

---

### Bug #6: session.save() 无回调/等待
- **文件**：`buggy-app/routes/auth.js:52`
- **严重性**：P1
- **类型**：可靠性
- **发现者**：Session 侦探、数据库侦探、架构侦探
- **描述**：登录成功后调用 `req.session.save()` 但不等待回调完成。Redis 故障时 save 静默失败，用户拿到 JWT 但 session 未持久化，后续请求发现 session 不存在。
- **代码片段**：
```javascript
// auth.js:52 — save 后不等待结果
req.session.save();

res.json({
  token,
  user: { id: user.id, email: user.email, name: user.name },
});
```
- **建议修复**：
```javascript
req.session.save((err) => {
  if (err) {
    console.error('Session save error:', err.message);
    return res.status(500).json({ error: 'Login failed - session error' });
  }
  res.json({
    token,
    user: { id: user.id, email: user.email, name: user.name },
  });
});
```

---

### Bug #7: saveUninitialized 设为 true
- **文件**：`buggy-app/middleware/session.js:31`
- **严重性**：P2
- **类型**：性能
- **发现者**：Session 侦探、架构侦探
- **描述**：每个请求（含未认证访客）都在 Redis 创建空 session。大量无意义写入增加 Redis 内存和写入压力，加剧 P1 和 P2 症状。
- **代码片段**：
```javascript
// session.js:31
saveUninitialized: true,
```
- **建议修复**：
```javascript
saveUninitialized: false,
```

---

### Bug #8: clearCache 范围过大（缓存雪崩触发器）
- **文件**：`buggy-app/routes/orders.js:156`
- **严重性**：P2
- **类型**：性能
- **发现者**：缓存侦探
- **描述**：任何用户创建订单时，`clearCache('orders')` 清除所有 orders 前缀的缓存条目。这导致缓存雪崩（thundering herd）：所有用户的请求同时打到数据库，加剧连接池耗尽。
- **代码片段**：
```javascript
// orders.js:156 — 清除所有用户的订单缓存
clearCache('orders');
```
- **建议修复**：
```javascript
// 只清除当前用户的缓存（前提是 Bug #1 修复后 key 包含 userId）
clearCache(`orders:${req.userId}`);
```

---

### Bug #9: 库存扣减缺少行级锁（并发超卖）
- **文件**：`buggy-app/routes/orders.js:110-111`
- **严重性**：P2
- **类型**：安全
- **发现者**：数据库侦探
- **描述**：`POST /api/orders` 中先 SELECT 检查库存，再 UPDATE 扣减，但 SELECT 未使用 `FOR UPDATE`。并发场景下两个请求可能同时读到相同库存量，都通过检查后各自扣减，导致超卖（库存变为负数）。
- **代码片段**：
```javascript
// orders.js:110-111 — SELECT 无行级锁
const productResult = await client.query(
  'SELECT id, price, stock FROM products WHERE id = $1',
  [item.productId]
);
```
- **建议修复**：
```javascript
const productResult = await client.query(
  'SELECT id, price, stock FROM products WHERE id = $1 FOR UPDATE',
  [item.productId]
);
```

---

### Bug #10: 缓存中间件在认证之前执行（架构缺陷 + 认证绕过）
- **文件**：`buggy-app/server.js:24`
- **严重性**：P0
- **类型**：安全（架构）
- **发现者**：缓存侦探首先发现，Session 侦探与架构侦探交叉分析后升级为 P0
- **描述**：`server.js:24` 中 `cacheMiddleware` 在 `orderRoutes`（内含 `authMiddleware`）之前执行。这导致两个严重问题：
  1. **认证绕过**：缓存命中时 `cache.js:27` 直接 `res.json(cached.data)` 返回，请求永远不会到达 `orders.js:9` 的 `authMiddleware`。**未携带 JWT token 的请求**只要 URL 匹配已有缓存 key，就能获取其他用户的订单数据。
  2. **userId undefined**：由于认证未执行，`req.userId` 为 `undefined`，即使修复缓存 key 加入 userId，key 仍为 `orders:undefined:/api/orders`，所有用户共享。P0 修复必须同时调整中间件顺序。
- **代码片段**：
```javascript
// server.js:24 — 缓存在认证之前，缓存命中时绕过认证
app.use('/api/orders', cacheMiddleware('orders', 300), orderRoutes);
// orderRoutes 内部: router.use(authMiddleware);  <-- 在 cache 之后，缓存命中时永远不执行

// cache.js:25-28 — 缓存命中直接返回，不经过后续中间件
if (cached && Date.now() - cached.timestamp < ttlSeconds * 1000) {
  return res.json(cached.data);  // 直接返回，认证被绕过
}
```
- **建议修复**：
```javascript
// 在 server.js 中将 authMiddleware 移到 cacheMiddleware 之前
const { authMiddleware } = require('./routes/auth');
app.use('/api/orders', authMiddleware, cacheMiddleware('orders', 300), orderRoutes);
```

---

## 级联关系分析

```
Bug #2 (连接池过小, db.js:12)
  + Bug #3 (N+1 查询, orders.js:30)
  + Bug #8 (clearCache 范围过大, orders.js:156)
  ──────────────────────────────────────────────
  │
  ├──▶ 直接导致 ──▶ 症状 P2：API 响应变慢
  │                  (高峰期 200ms → 3-5s)
  │
  ├──▶ 系统资源紧张 ──▶ Redis 连接不稳定
  │                       │
  │                       ▼
  │                 Bug #4 (Redis 无重连)
  │                 + Bug #5 (Redis 无就绪检查)
  │                 + Bug #6 (session.save 无回调)
  │                 + Bug #7 (saveUninitialized=true 写放大)
  │                       │
  │                       ▼
  └──▶ 级联导致 ──▶ 症状 P1：会话丢失
                     (高峰期 5% 用户受影响)

Bug #1 (缓存 key 无用户标识, cache.js:22)
  + Bug #10 (缓存在认证前执行, server.js:24)
  ──────────────────────────────────────────
  │
  └──▶ 独立导致 ──▶ 症状 P0：数据泄漏
                     (高峰期更容易触发，因 P2 延长了竞态窗口)
```

**关键洞察**：三个症状看似独立，实则存在深层因果链。单独修复某个 bug 不够 —— 例如仅修复缓存 key（Bug #1）能解决 P0，但 P1 和 P2 仍会存在；仅扩大连接池（Bug #2）能缓解 P2，但 Redis 层的脆弱性仍会导致 P1。必须理解级联关系，系统性地修复。

---

## 与用户报告的症状对应

| 用户症状 | 直接原因 | 根本原因 | 涉及的 Bug |
|---------|---------|---------|-----------|
| 会话丢失 (P1) | Redis 断连后 session 静默丢失，save 不等待回调 | DB 连接池耗尽导致系统资源紧张，级联影响 Redis 连接 | #2, #3, #4, #5, #6, #7 |
| API 变慢 (P2) | 高峰期数据库连接排队等待超时 | 连接池过小 + N+1 查询 + 缓存雪崩三重叠加 | #2, #3, #8 |
| 数据泄漏 (P0) | 缓存返回了其他用户的订单数据，且缓存命中时绕过认证 | 缓存 key 不含用户标识 + 缓存在认证前执行（认证绕过） | #1, #10 |

---

## 修复优先级建议

1. **立即修复（P0 安全漏洞）**：Bug #1 + Bug #10（缓存 key 加入 userId + 调整中间件顺序）
   - 原因：数据泄漏是最严重的安全事件，即使频率低也必须立即修复
   - 预计影响：彻底解决 P0 症状

2. **其次修复（P1/P2 性能与可靠性）**：Bug #2 + Bug #3（扩大连接池 + 消除 N+1 查询）
   - 原因：这是 P2 的直接根因，也是 P1 的级联触发器。修复后同时缓解两个症状
   - 预计影响：P2 基本解决，P1 大幅缓解

3. **随后修复（P1 可靠性加固）**：Bug #4 + Bug #5 + Bug #6 + Bug #7（Redis 重连 + 就绪检查 + save 回调 + saveUninitialized=false）
   - 原因：即使连接池修复后系统压力降低，Redis 层仍需增强健壮性以应对各种故障场景
   - 预计影响：彻底解决 P1 症状

4. **后续优化**：Bug #8（精细化缓存清除）、Bug #9（库存行级锁）
   - 原因：缓存雪崩和超卖是潜在风险，但非当前症状的主因
   - 预计影响：提升系统整体健壮性

---

## 调查过程记录

### Teammate 发现

- **Session 侦探**：发现 5 个 bug（S1-S5），集中在 Redis 连接管理和 session 持久化。率先提出三症状因果链假设：P2 级联触发 P1，P0 独立存在。同时跨层指出缓存 key 和 N+1 查询问题。
- **数据库侦探**：发现 5 个 bug（DB1-DB5），确认连接池不足和 N+1 查询是 P2 根因。独立发现库存扣减无行级锁的并发安全问题。验证了 session 侦探的因果链分析。
- **缓存侦探**：发现 4 个问题，独立确认缓存 key 无用户标识是 P0 根因。额外发现缓存雪崩（thundering herd）机制和 clearCache 范围过大的问题。关键架构发现：缓存中间件在 auth 之前执行（server.js:24）。
- **架构侦探**：绘制完整组件交互图，从全局视角确认所有 bug。独立验证了因果链：Bug #3 耗尽 Bug #2 → P2；系统压力 → Bug #4 → P1；Bug #1 → P0。

### 关键交叉发现

1. **缓存 key 无用户标识**（Bug #1）：全部 4 名侦探独立发现，交叉验证度最高。
2. **P2 级联触发 P1**：Session 侦探首先提出假设，数据库侦探和架构侦探独立验证。连接池耗尽 → Redis 不稳定 → session 丢失，这条因果链被三方确认。
3. **缓存中间件执行顺序与认证绕过**（Bug #10）：缓存侦探首先发现 `server.js:24` 中 cacheMiddleware 在 authMiddleware 之前。Session 侦探与架构侦探交叉分析后发现更严重的问题——**缓存命中时完全绕过认证**，未携带 JWT 的请求也能获取数据。数据库侦探进一步确认：即使修复 cache key 加入 userId，由于 auth 未执行，`req.userId` 为 `undefined`，key 仍然共享。因此 **P0 修复必须同时调整中间件顺序 + 修改 cache key**，缺一不可。
4. **缓存雪崩效应**：缓存侦探发现 `clearCache('orders')` 会一次性清除所有缓存，触发 thundering herd。数据库侦探的连接池分析证实了这会加剧 P2。
5. **全团队共识**：所有 4 名侦探对因果链分析完全一致——P2（连接池+N+1）级联触发 P1（Redis 层脆弱），P0（缓存 key+认证绕过）独立存在但被 P2 的慢查询放大。
