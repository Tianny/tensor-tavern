const express = require('express');
const jwt = require('jsonwebtoken');
const { query } = require('../db');

const router = express.Router();

const JWT_SECRET = process.env.JWT_SECRET || 'shopstream-jwt-secret';

/**
 * POST /api/auth/login
 * Authenticate user and return JWT token
 */
router.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return res.status(400).json({ error: 'Email and password required' });
    }

    const result = await query(
      'SELECT id, email, name, password_hash FROM users WHERE email = $1',
      [email]
    );

    if (result.rows.length === 0) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    const user = result.rows[0];

    // Simplified password check (assume bcrypt.compare in real app)
    // In production this would be: await bcrypt.compare(password, user.password_hash)
    if (password !== user.password_hash) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    // Generate JWT
    const token = jwt.sign(
      { userId: user.id, email: user.email },
      JWT_SECRET,
      { expiresIn: '24h' }
    );

    // Store user info in session
    req.session.userId = user.id;
    req.session.email = user.email;

    // BUG 隐患: session.save() 没有等待回调
    // 如果 Redis 连接有问题，save 会静默失败
    // 用户拿到了 JWT token 但 session 没有被持久化
    req.session.save();

    res.json({
      token,
      user: { id: user.id, email: user.email, name: user.name },
    });
  } catch (err) {
    console.error('Login error:', err.message);
    res.status(500).json({ error: 'Login failed' });
  }
});

/**
 * Middleware: verify JWT token
 */
function authMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'No token provided' });
  }

  const token = authHeader.split(' ')[1];

  try {
    // BUG 潜在隐患: jwt.verify 没有校验 issuer
    // 开发环境和生产环境使用相同的 secret 时，
    // 开发环境签发的 token 可以在生产环境使用
    const decoded = jwt.verify(token, JWT_SECRET);
    req.userId = decoded.userId;
    req.userEmail = decoded.email;
    next();
  } catch (err) {
    return res.status(401).json({ error: 'Invalid token' });
  }
}

/**
 * POST /api/auth/logout
 */
router.post('/logout', (req, res) => {
  req.session.destroy((err) => {
    if (err) {
      console.error('Logout error:', err.message);
    }
    res.json({ message: 'Logged out' });
  });
});

module.exports = router;
module.exports.authMiddleware = authMiddleware;
