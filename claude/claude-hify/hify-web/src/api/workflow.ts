import { get, post, put, del } from '@/utils/request'

// ---------------- 节点 / 连线 ----------------

export type NodeType = 'start' | 'llm' | 'condition' | 'end'

export interface StartVariable {
  name: string
  required: boolean
}

/** LLM 节点配置（后端按 nodeType 约定校验） */
export interface LlmNodeConfig {
  modelConfigId: number
  prompt: string
  temperature?: number
  maxTokens?: number
}

export interface StartNodeConfig {
  variables: StartVariable[]
}

export interface EndNodeConfig {
  outputTemplate: string
}

export interface WorkflowNode {
  id: number
  nodeKey: string
  nodeType: NodeType
  name: string
  config: Record<string, unknown>
}

export interface WorkflowEdge {
  id: number
  sourceNodeKey: string
  targetNodeKey: string
  conditionType: 'always' | 'expression'
  conditionExpr: string
}

// ---------------- 工作流 ----------------

export interface Workflow {
  id: number
  name: string
  description: string
  enabled: number
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  createdAt: string
  updatedAt: string
}

export interface WorkflowSaveReq {
  name: string
  description?: string
  enabled?: number
  nodes: Omit<WorkflowNode, 'id'>[]
  edges: Omit<WorkflowEdge, 'id'>[]
}

export function listWorkflows(): Promise<Workflow[]> {
  return get('/v1/workflows')
}

export function getWorkflow(id: number): Promise<Workflow> {
  return get(`/v1/workflows/${id}`)
}

export function createWorkflow(data: WorkflowSaveReq): Promise<Workflow> {
  return post('/v1/workflows', data)
}

export function updateWorkflow(id: number, data: WorkflowSaveReq): Promise<Workflow> {
  return put(`/v1/workflows/${id}`, data)
}

export function deleteWorkflow(id: number): Promise<void> {
  return del(`/v1/workflows/${id}`)
}

// ---------------- 运行 ----------------

/** 节点执行轨迹 */
export interface NodeTrace {
  nodeKey: string
  nodeType: NodeType
  name: string
  input: string
  output: string
  error: string | null
  latencyMs: number
}

export interface WorkflowRun {
  id: number
  workflowId: number
  status: 'running' | 'succeeded' | 'failed'
  inputs: Record<string, string>
  output: string
  errorMsg: string
  traces: NodeTrace[]
  latencyMs: number
  createdAt: string
}

export interface WorkflowRunSummary {
  id: number
  workflowId: number
  status: 'running' | 'succeeded' | 'failed'
  output: string
  errorMsg: string
  latencyMs: number
  createdAt: string
}

/** 提交运行（异步），返回运行记录 id */
export function runWorkflow(id: number, data: { inputs: Record<string, string> }): Promise<number> {
  return post(`/v1/workflows/${id}/run`, data)
}

/** 运行详情（轮询用，含轨迹） */
export function getWorkflowRun(runId: number): Promise<WorkflowRun> {
  return get(`/v1/workflow-runs/${runId}`)
}

/** 最近运行记录（不含轨迹） */
export function listWorkflowRuns(id: number, limit = 20): Promise<WorkflowRunSummary[]> {
  return get(`/v1/workflows/${id}/runs`, { limit })
}
