import { get, post, del } from '@/utils/request'

export interface ChatSession {
  id: number
  /** Agent 会话的 agent.id，工作流会话为 null */
  agentId: number | null
  /** 工作流会话的 workflow.id，Agent 会话为 null */
  workflowId: number | null
  title: string
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: number
  sessionId: number
  role: 'user' | 'assistant'
  content: string
  promptTokens: number
  completionTokens: number
  latencyMs: number
  createdAt: string
}

export interface SessionCreateReq {
  /** 与 workflowId 二选一 */
  agentId?: number
  /** 与 agentId 二选一：消息走工作流执行 */
  workflowId?: number
  title?: string
}

/** GET /api/v1/sessions 会话列表（按 agent 或 workflow 过滤，最后活跃倒序） */
export function listSessions(agentId?: number, workflowId?: number): Promise<ChatSession[]> {
  return get('/v1/sessions', { agentId, workflowId })
}

export function createSession(data: SessionCreateReq): Promise<ChatSession> {
  return post('/v1/sessions', data)
}

export function deleteSession(id: number): Promise<void> {
  return del(`/v1/sessions/${id}`)
}

/**
 * GET /api/v1/sessions/{id}/messages 游标分页（不做 COUNT）：
 * before 为空取最新一页，否则取 id < before 的更早消息；返回时间正序。
 */
export function listMessages(sessionId: number, before?: number, limit = 20): Promise<ChatMessage[]> {
  return get(`/v1/sessions/${sessionId}/messages`, { before, limit })
}
