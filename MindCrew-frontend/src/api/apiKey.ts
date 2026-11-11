import request from '@/utils/request'

export interface ApiKey {
  id: number
  name: string
  keyPrefix: string
  allowedKbIds?: string         // JSON string
  monthlyQuota: number
  monthUsed: number
  rateLimitQps: number
  totalCalls: number
  lastUsedAt?: string
  expireAt?: string
  status: 'active' | 'revoked' | 'expired'
  description?: string
  createdBy?: number
  createTime?: string
}

export interface ApiCallLog {
  id: number
  keyId: number
  kbId?: number
  api: string
  question?: string
  statusCode: number
  inputTokens: number
  outputTokens: number
  costCny: number
  latencyMs: number
  ip?: string
  userAgent?: string
  errorMsg?: string
  calledAt: string
}

export interface IssueResult {
  id: number
  rawKey: string          // 完整 key · 仅此一次返回
  prefix: string
  warning: string
}

export const apiKeyApi = {
  /** 生成新 API Key · 返回的 rawKey 必须立即让用户复制保存 */
  issue: (data: {
    name: string
    allowedKbIds: number[]
    monthlyQuota?: number
    rateLimitQps?: number
    expireAt?: string
    description?: string
  }): Promise<any> => request.post('/v2/api-key', data),

  page: (params: { current?: number; size?: number; kbId?: number; status?: string }): Promise<any> =>
    request.get('/v2/api-key/page', { params }),

  /** 11.6 · 列出某 KB 的所有 API Key */
  byKb: (kbId: number): Promise<any> => request.get(`/v2/api-key/by-kb/${kbId}`),

  revoke: (id: number): Promise<any> => request.post(`/v2/api-key/${id}/revoke`),

  updateQuota: (id: number, data: { monthlyQuota?: number; rateLimitQps?: number }): Promise<any> =>
    request.put(`/v2/api-key/${id}/quota`, data),

  updateAllowedKbs: (id: number, allowedKbIds: number[]): Promise<any> =>
    request.put(`/v2/api-key/${id}/kbs`, { allowedKbIds }),

  delete: (id: number): Promise<any> => request.delete(`/v2/api-key/${id}`),

  logs: (params: { current?: number; size?: number; keyId?: number; kbId?: number }): Promise<any> =>
    request.get('/v2/api-key/logs', { params }),
}
