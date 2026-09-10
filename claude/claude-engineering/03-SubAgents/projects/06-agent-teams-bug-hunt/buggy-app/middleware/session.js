const session = require('express-session');
const RedisStore = require('connect-redis').default;
const { createClient } = require('redis');

function setupSession(app) {
  // 创建 Redis 客户端
  const redisClient = createClient({
    url: process.env.REDIS_URL || 'redis://localhost:6379',
  });

  // BUG #2: Redis 连接错误只是打日志，没有重连逻辑
  // 当 Redis 连接断开后（比如 DB 连接池耗尽导致系统资源紧张），
  // session 存储会静默失败——用户的 session 写入丢失但不报错
  redisClient.on('error', (err) => {
    console.error('Redis session error:', err.message);
    // 没有重连！没有 fallback！没有告警！
  });

  // 连接 Redis（不等待连接成功就继续）
  redisClient.connect().catch(console.error);

  const store = new RedisStore({ client: redisClient });

  app.use(session({
    store: store,
    secret: process.env.SESSION_SECRET || 'shopstream-secret-key-2024',
    resave: false,
    // BUG #2 续: saveUninitialized 设为 true
    // 这意味着即使 session 是空的也会保存到 Redis
    // 当 Redis 连接有问题时，会产生大量无意义的写入尝试
    saveUninitialized: true,
    cookie: {
      secure: process.env.NODE_ENV === 'production',
      httpOnly: true,
      maxAge: 24 * 60 * 60 * 1000, // 24 hours
    },
    // 没有配置 session touch 间隔
    // 每次请求都会 touch session，增加 Redis 写入压力
  }));

  return store;
}

module.exports = { setupSession };
