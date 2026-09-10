const express = require('express');
const { query } = require('../db');
const { authMiddleware } = require('./auth');

const router = express.Router();

// All routes require authentication
router.use(authMiddleware);

/**
 * GET /api/users/me
 * Get current user profile
 */
router.get('/me', async (req, res) => {
  try {
    const result = await query(
      'SELECT id, email, name, created_at FROM users WHERE id = $1',
      [req.userId]
    );

    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'User not found' });
    }

    res.json(result.rows[0]);
  } catch (err) {
    console.error('Get user error:', err.message);
    res.status(500).json({ error: 'Failed to get user' });
  }
});

/**
 * PUT /api/users/me
 * Update current user profile
 */
router.put('/me', async (req, res) => {
  try {
    const { name } = req.body;

    if (!name) {
      return res.status(400).json({ error: 'Name is required' });
    }

    const result = await query(
      'UPDATE users SET name = $1, updated_at = NOW() WHERE id = $2 RETURNING id, email, name',
      [name, req.userId]
    );

    res.json(result.rows[0]);
  } catch (err) {
    console.error('Update user error:', err.message);
    res.status(500).json({ error: 'Failed to update user' });
  }
});

module.exports = router;
