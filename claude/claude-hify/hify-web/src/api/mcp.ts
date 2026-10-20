import { get, post, put, del } from '@/utils/request'

// ---------------- MCP 服务 ----------------

export interface McpServer {
  id: number
  name: string
  description: string
  transport: string
  url: string
  /** 值为掩码 ******（真实值只在库里，更新时原样回传即保持原值） */
  headers: Record<string, string>
  enabled: number
  createdAt: string
  updatedAt: string
}

export interface McpServerSaveReq {
  name: string
  description?: string
  transport?: string
  url: string
  headers?: Record<string, string> | null
  enabled?: number
}

export interface McpTestResp {
  ok: boolean
  message: string
  latencyMs: number
  toolCount: number | null
  testedAt: string
}

export interface McpTool {
  name: string
  description: string
  inputSchema: Record<string, unknown> | null
}

export function listMcpServers(): Promise<McpServer[]> {
  return get('/v1/mcp-servers')
}

export function createMcpServer(data: McpServerSaveReq): Promise<McpServer> {
  return post('/v1/mcp-servers', data)
}

export function updateMcpServer(id: number, data: McpServerSaveReq): Promise<McpServer> {
  return put(`/v1/mcp-servers/${id}`, data)
}

export function deleteMcpServer(id: number): Promise<void> {
  return del(`/v1/mcp-servers/${id}`)
}

/** 连通性测试：initialize 握手 + tools/list，失败 ok=false 正常返回 */
export function testMcpConnection(id: number): Promise<McpTestResp> {
  return post(`/v1/mcp-servers/${id}/test-connection`)
}

/** 工具列表（实时拉取） */
export function listMcpTools(id: number): Promise<McpTool[]> {
  return get(`/v1/mcp-servers/${id}/tools`)
}

// ---------------- Agent 工具绑定 ----------------

export interface AgentToolBinding {
  mcpServerId: number
  /** null/空 = 绑定该服务全部工具 */
  toolName: string | null
}

export interface AgentToolBindingResp {
  mcpServerId: number
  serverName: string
  toolName: string | null
}

export function listAgentTools(agentId: number): Promise<AgentToolBindingResp[]> {
  return get(`/v1/agents/${agentId}/tools`)
}

/** 全量覆盖绑定（空数组 = 全部解绑） */
export function updateAgentTools(agentId: number, data: { bindings: AgentToolBinding[] }): Promise<void> {
  return put(`/v1/agents/${agentId}/tools`, data)
}
