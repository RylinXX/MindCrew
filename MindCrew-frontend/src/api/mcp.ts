import request from '@/utils/request'

export interface McpTool {
  id: number
  name: string
  description: string
  mode: 'embedded' | 'remote'
  callCount: number
  avgLatencyMs: number
  status: 'active' | 'disabled'
  createTime: string
  updateTime: string
}

export interface McpTestResult {
  toolId: number
  toolName: string
  mode: string
  status: string
  testResult: 'SUCCESS' | 'ERROR' | 'SKIPPED'
  message: string
  latencyMs: number
  timestamp: number
  sampleOutput?: unknown
}

export const mcpApi = {
  listTools: (): Promise<McpTool[]> =>
    request.get('/mcp/tools'),

  updateStatus: (id: number, status: 'active' | 'disabled'): Promise<void> =>
    request.put(`/mcp/tools/${id}/status`, { status }),

  testTool: (id: number): Promise<McpTestResult> =>
    request.post(`/mcp/tools/${id}/test`),
}
