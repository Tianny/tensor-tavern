import { get, post, put, del } from '@/utils/request'
import type { PageQuery, PageResult } from '@/types/api'

export interface Agent {
  id: number
  name: string
  description: string
  modelConfigId: number
  /** 联表字段：模型配置已删除时为空串，前端标红提示 */
  modelName: string
  providerId: number | null
  providerName: string
  systemPrompt: string
  temperature: number
  /** 单次回复 token 上限，0=用模型默认 */
  maxTokens: number
  enabled: number
  createdAt: string
  updatedAt: string
}

export interface AgentSaveReq {
  name: string
  description?: string
  modelConfigId: number
  systemPrompt?: string
  temperature?: number
  maxTokens?: number
  enabled?: number
}

/** GET /api/v1/agents 分页列表（name 模糊） */
export function listAgents(params: PageQuery & { name?: string }): Promise<PageResult<Agent>> {
  return get('/v1/agents', { ...params })
}

export function createAgent(data: AgentSaveReq): Promise<Agent> {
  return post('/v1/agents', data)
}

export function updateAgent(id: number, data: AgentSaveReq): Promise<Agent> {
  return put(`/v1/agents/${id}`, data)
}

export function deleteAgent(id: number): Promise<void> {
  return del(`/v1/agents/${id}`)
}
