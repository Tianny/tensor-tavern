const express = require('express');
const { query, getClient } = require('../db');
const { authMiddleware } = require('./auth');
const { clearCache } = require('../middleware/cache');

const router = express.Router();

// All routes require authentication
router.use(authMiddleware);

/**
 * GET /api/orders
 * List orders for the current user
 */
router.get('/', async (req, res) => {
  try {
    // 获取用户的所有订单
    const ordersResult = await query(
      'SELECT id, status, total, created_at FROM orders WHERE user_id = $1 ORDER BY created_at DESC',
      [req.userId]
    );

    const orders = ordersResult.rows;

    // BUG #3: 经典的 N+1 查询问题
    // 对每个订单都单独查询其订单项
    // 如果用户有 50 个订单，就会执行 51 次数据库查询
    // 每次查询都需要从连接池获取连接
    // 高峰期这会迅速耗尽连接池（加剧 Bug #1）
    for (const order of orders) {
      const itemsResult = await query(
        'SELECT oi.id, oi.quantity, oi.price, p.name as product_name ' +
        'FROM order_items oi ' +
        'JOIN products p ON p.id = oi.product_id ' +
        'WHERE oi.order_id = $1',
        [order.id]
      );
      order.items = itemsResult.rows;
    }

    res.json({ orders, total: orders.length });
  } catch (err) {
    console.error('List orders error:', err.message);
    res.status(500).json({ error: 'Failed to list orders' });
  }
});

/**
 * GET /api/orders/:id
 * Get a specific order with details
 */
router.get('/:id', async (req, res) => {
  try {
    const { id } = req.params;

    const orderResult = await query(
      'SELECT id, user_id, status, total, created_at FROM orders WHERE id = $1',
      [id]
    );

    if (orderResult.rows.length === 0) {
      return res.status(404).json({ error: 'Order not found' });
    }

    const order = orderResult.rows[0];

    // 权限检查：确保用户只能看自己的订单
    if (order.user_id !== req.userId) {
      return res.status(403).json({ error: 'Access denied' });
    }

    // 获取订单项（这里是单个订单所以 N+1 不严重）
    const itemsResult = await query(
      'SELECT oi.id, oi.quantity, oi.price, p.name as product_name ' +
      'FROM order_items oi ' +
      'JOIN products p ON p.id = oi.product_id ' +
      'WHERE oi.order_id = $1',
      [order.id]
    );

    order.items = itemsResult.rows;
    res.json(order);
  } catch (err) {
    console.error('Get order error:', err.message);
    res.status(500).json({ error: 'Failed to get order' });
  }
});

/**
 * POST /api/orders
 * Create a new order
 */
router.post('/', async (req, res) => {
  const client = await getClient();

  try {
    const { items } = req.body; // [{ productId, quantity }]

    if (!items || items.length === 0) {
      return res.status(400).json({ error: 'Order must have at least one item' });
    }

    await client.query('BEGIN');

    // Calculate total
    let total = 0;
    const orderItems = [];

    for (const item of items) {
      const productResult = await client.query(
        'SELECT id, price, stock FROM products WHERE id = $1',
        [item.productId]
      );

      if (productResult.rows.length === 0) {
        await client.query('ROLLBACK');
        return res.status(400).json({ error: `Product ${item.productId} not found` });
      }

      const product = productResult.rows[0];

      if (product.stock < item.quantity) {
        await client.query('ROLLBACK');
        return res.status(400).json({ error: `Insufficient stock for product ${item.productId}` });
      }

      total += product.price * item.quantity;
      orderItems.push({ productId: item.productId, quantity: item.quantity, price: product.price });

      // Update stock
      await client.query(
        'UPDATE products SET stock = stock - $1 WHERE id = $2',
        [item.quantity, item.productId]
      );
    }

    // Create order
    const orderResult = await client.query(
      'INSERT INTO orders (user_id, total, status) VALUES ($1, $2, $3) RETURNING id',
      [req.userId, total, 'pending']
    );

    const orderId = orderResult.rows[0].id;

    // Create order items
    for (const item of orderItems) {
      await client.query(
        'INSERT INTO order_items (order_id, product_id, quantity, price) VALUES ($1, $2, $3, $4)',
        [orderId, item.productId, item.quantity, item.price]
      );
    }

    await client.query('COMMIT');

    // Clear orders cache for this user
    clearCache('orders');

    res.status(201).json({ orderId, total, status: 'pending' });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Create order error:', err.message);
    res.status(500).json({ error: 'Failed to create order' });
  } finally {
    // BUG #3 关联: 注意这里正确释放了 client
    // 但是上面 GET /orders 中的 N+1 查询用的是 pool.query
    // pool.query 内部会自动获取和释放连接
    // 问题是：50 次 pool.query 在高峰期可能同时持有多个连接
    client.release();
  }
});

module.exports = router;
