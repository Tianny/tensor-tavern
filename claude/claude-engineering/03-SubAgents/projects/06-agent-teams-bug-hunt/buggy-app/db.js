const { Pool } = require('pg');

const pool = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: process.env.DB_PORT || 5432,
  database: process.env.DB_NAME || 'shopstream',
  user: process.env.DB_USER || 'shopstream',
  password: process.env.DB_PASS || 'secret',
  // BUG #1: 连接池最大连接数设置过小
  // 生产环境有 50+ 并发用户，但只有 5 个连接
  // 高峰期会导致连接等待甚至超时
  max: 5,
  // 空闲超时也过短，连接频繁被回收又重建
  idleTimeoutMillis: 5000,
  // 连接超时只有 3 秒，高峰期排队时很容易超时
  connectionTimeoutMillis: 3000,
});

// 连接错误处理——但只是打日志，没有任何恢复机制
pool.on('error', (err) => {
  console.error('Unexpected database pool error:', err.message);
});

/**
 * Execute a query with the connection pool
 */
async function query(text, params) {
  const start = Date.now();
  try {
    const result = await pool.query(text, params);
    const duration = Date.now() - start;
    if (duration > 1000) {
      console.warn(`Slow query (${duration}ms): ${text.substring(0, 100)}`);
    }
    return result;
  } catch (err) {
    console.error(`Query error: ${err.message}`);
    throw err;
  }
}

/**
 * Get a dedicated client from the pool
 * NOTE: Caller MUST call client.release() when done!
 */
async function getClient() {
  const client = await pool.connect();
  return client;
}

module.exports = { pool, query, getClient };
