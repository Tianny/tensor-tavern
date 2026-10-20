import { get, post, put, del } from '@/utils/request'
import type { PageQuery, PageResult } from '@/types/api'

export type ModelType = 'chat' | 'embedding'

export interface ModelConfig {
  id: number
  providerId: number
  modelName: string
  displayName: string
  modelType: ModelType
  /** 上下文窗口 token 上限，0=未知 */
  maxContextTokens: number
  enabled: number
  createdAt: string
  updatedAt: string
}

export interface ModelConfigCreateReq {
  providerId: number
  modelName: string
  displayName?: string
  modelType: ModelType
  maxContextTokens?: number
  enabled?: number
}

export interface ModelConfigUpdateReq {
  modelName: string
  displayName?: string
  modelType: ModelType
  maxContextTokens?: number
  enabled?: number
}

/** GET /api/v1/model-configs 分页列表（providerId / modelType 过滤） */
export function listModelConfigs(
  params: PageQuery & { providerId?: number; modelType?: string },
): Promise<PageResult<ModelConfig>> {
  return get('/v1/model-configs', { ...params })
}

export function createModelConfig(data: ModelConfigCreateReq): Promise<ModelConfig> {
  return post('/v1/model-configs', data)
}

export function updateModelConfig(id: number, data: ModelConfigUpdateReq): Promise<ModelConfig> {
  return put(`/v1/model-configs/${id}`, data)
}

export function deleteModelConfig(id: number): Promise<void> {
  return del(`/v1/model-configs/${id}`)
}

/** GET /api/v1/providers/{providerId}/remote-models 拉取远端模型名（只读，不入库） */
export function fetchRemoteModels(providerId: number): Promise<string[]> {
  return get(`/v1/providers/${providerId}/remote-models`)
}
