import { get, post, put, del } from '@/utils/request'
import type { PageQuery, PageResult } from '@/types/api'

/** Provider 类型（与后端 ProviderType 枚举 code 一一对应） */
export type ProviderTypeCode = 'openai' | 'openai_compatible' | 'claude' | 'gemini' | 'ollama'

export interface Provider {
  id: number
  name: string
  type: ProviderTypeCode
  baseUrl: string
  /** 掩码后的 Key（****abcd），明文不出服务端 */
  apiKeyMasked: string
  enabled: number
  remark: string
  lastTestAt: string | null
  /** 0=失败 1=成功，null=从未测试 */
  lastTestOk: number | null
  lastTestMessage: string | null
  createdAt: string
  updatedAt: string
}

/** 新增 / 更新共用（更新时 apiKey 留空 = 保持原值） */
export interface ProviderSaveReq {
  name: string
  type: ProviderTypeCode
  baseUrl?: string
  apiKey?: string
  enabled?: number
  remark?: string
}

export interface TestConnectionResp {
  ok: boolean
  message: string
  latencyMs: number
  modelCount: number | null
  testedAt: string
}

/** GET /api/v1/providers 分页列表 */
export function listProviders(params: PageQuery): Promise<PageResult<Provider>> {
  return get('/v1/providers', { ...params })
}

export function createProvider(data: ProviderSaveReq): Promise<Provider> {
  return post('/v1/providers', data)
}

export function updateProvider(id: number, data: ProviderSaveReq): Promise<Provider> {
  return put(`/v1/providers/${id}`, data)
}

export function deleteProvider(id: number): Promise<void> {
  return del(`/v1/providers/${id}`)
}

/** POST /api/v1/providers/{id}/test-connection 连通性测试 */
export function testProviderConnection(id: number): Promise<TestConnectionResp> {
  return post(`/v1/providers/${id}/test-connection`)
}
