/**
 * In-memory cache middleware for API responses.
 *
 * Caches responses keyed by URL path to reduce database load.
 */

const cache = new Map();

/**
 * Create a caching middleware for the given prefix and TTL.
 */
function cacheMiddleware(prefix, ttlSeconds) {
  return (req, res, next) => {
    // Only cache GET requests
    if (req.method !== 'GET') {
      return next();
    }

    // BUG #4: 缓存 key 只用了 URL path，没有包含用户标识！
    // 这意味着 /api/orders 对所有用户返回相同的缓存结果
    // 当用户 A 的请求被缓存后，用户 B 会看到用户 A 的数据
    const cacheKey = `${prefix}:${req.originalUrl}`;

    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < ttlSeconds * 1000) {
      // 缓存命中，直接返回
      return res.json(cached.data);
    }

    // 缓存未命中或已过期
    // BUG #4 续: 如果缓存刚好过期，在新数据写入缓存之前，
    // 存在一个时间窗口。在这个窗口内：
    // 1. 缓存条目被删除
    // 2. 多个用户的请求同时到达
    // 3. 第一个完成数据库查询的用户的数据被写入缓存
    // 4. 后续用户的请求在缓存被写入后、但在自己的查询完成前命中缓存
    // 5. 结果：后续用户看到第一个用户的数据
    if (cached) {
      cache.delete(cacheKey); // 删除过期缓存
    }

    // 劫持 res.json 来捕获响应并缓存
    const originalJson = res.json.bind(res);
    res.json = (data) => {
      // 写入缓存（不管是哪个用户的数据）
      cache.set(cacheKey, {
        data: data,
        timestamp: Date.now(),
      });
      return originalJson(data);
    };

    next();
  };
}

/**
 * Clear cache entries matching a prefix
 */
function clearCache(prefix) {
  for (const key of cache.keys()) {
    if (key.startsWith(prefix)) {
      cache.delete(key);
    }
  }
}

module.exports = { cacheMiddleware, clearCache };
