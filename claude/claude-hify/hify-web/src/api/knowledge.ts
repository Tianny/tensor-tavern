import instance, { get, post, put, del } from '@/utils/request'

/** 知识库 */
export interface KnowledgeBase {
  id: number
  name: string
  description: string
  embeddingModelId: number
  /** 向量化模型展示名，模型被删则为空串 */
  embeddingModelName: string
  embeddingProviderName: string
  createdAt: string
}

export interface KnowledgeBaseCreateReq {
  name: string
  description?: string
  embeddingModelId: number
}

export interface KnowledgeBaseUpdateReq {
  name: string
  description?: string
}

/** 知识库文档 */
export interface KnowledgeDocument {
  id: number
  knowledgeBaseId: number
  fileName: string
  fileType: string
  fileSize: number
  /** pending / processing / completed / failed */
  status: 'pending' | 'processing' | 'completed' | 'failed'
  errorMsg: string
  chunkCount: number
  createdAt: string
}

export interface Chunk {
  chunkIndex: number
  content: string
  tokenCount: number
}

/** 检索命中 */
export interface RetrievalHit {
  rank: number
  documentId: number
  documentName: string
  chunkIndex: number
  content: string
  /** 余弦相似度 [0,1] */
  score: number
}

export interface AgentKnowledgeUpdateReq {
  /** 绑定的知识库 id 列表，空数组 = 全部解绑 */
  knowledgeBaseIds: number[]
}

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return get('/v1/knowledge-bases')
}

export function createKnowledgeBase(data: KnowledgeBaseCreateReq): Promise<KnowledgeBase> {
  return post('/v1/knowledge-bases', data)
}

export function updateKnowledgeBase(id: number, data: KnowledgeBaseUpdateReq): Promise<KnowledgeBase> {
  return put(`/v1/knowledge-bases/${id}`, data)
}

export function deleteKnowledgeBase(id: number): Promise<void> {
  return del(`/v1/knowledge-bases/${id}`)
}

export function listDocuments(kbId: number): Promise<KnowledgeDocument[]> {
  return get(`/v1/knowledge-bases/${kbId}/documents`)
}

/** multipart 上传（axios 对 FormData 自动带 boundary，不能手动设 Content-Type） */
export function uploadDocument(kbId: number, file: File): Promise<KnowledgeDocument> {
  const form = new FormData()
  form.append('file', file)
  return instance.post(`/v1/knowledge-bases/${kbId}/documents`, form) as unknown as Promise<KnowledgeDocument>
}

export function deleteDocument(id: number): Promise<void> {
  return del(`/v1/documents/${id}`)
}

export function retryDocument(id: number): Promise<void> {
  return post(`/v1/documents/${id}/retry`)
}

export function listChunks(documentId: number, params?: { offset?: number; limit?: number }): Promise<Chunk[]> {
  return get(`/v1/documents/${documentId}/chunks`, { ...params })
}

/** 检索测试 */
export function searchKnowledgeBase(
  kbId: number,
  data: { query: string; topK?: number },
): Promise<RetrievalHit[]> {
  return post(`/v1/knowledge-bases/${kbId}/search`, data)
}

/** Agent 绑定的知识库（表单回显用） */
export function listAgentKnowledgeBases(agentId: number): Promise<KnowledgeBase[]> {
  return get(`/v1/agents/${agentId}/knowledge-bases`)
}

/** 全量覆盖 Agent 的知识库绑定 */
export function updateAgentKnowledgeBases(agentId: number, data: AgentKnowledgeUpdateReq): Promise<void> {
  return put(`/v1/agents/${agentId}/knowledge-bases`, data)
}
