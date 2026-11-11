import request from '@/utils/request'

export interface ConvSearchParams {
  keyword?: string
  userId?: number
  deptId?: number
  from?: string
  to?: string
  onlyFlagged?: boolean
  current?: number
  size?: number
}

export interface MessageSnippet {
  messageId: number
  role: string
  snippet: string
  createTime: string
}

export interface ConvMatch {
  id: number
  title: string
  userId: number
  username?: string
  nickname?: string
  departmentId?: number
  departmentName?: string
  messageCount: number
  lastActive: string
  isFlagged?: number
  flagNote?: string
  flaggedByName?: string
  flaggedAt?: string
  matchedSnippets?: MessageSnippet[]
}

export interface ConvSearchResult {
  total: number
  current: number
  size: number
  records: ConvMatch[]
}

export interface ConvDetail {
  conversation: {
    id: number
    title: string
    userId: number
    kbIds?: string
    messageCount: number
    lastActive: string
    isFlagged?: number
    flagNote?: string
    flaggedBy?: number
    flaggedAt?: string
  }
  username?: string
  nickname?: string
  departmentName?: string
  messages: Array<{
    id: number
    role: string
    content: string
    sources?: string
    retrievalLog?: string
    feedback?: number
    createTime: string
  }>
}

export const adminConvApi = {
  search: (params: ConvSearchParams): Promise<any> =>
    request.get('/v2/admin/conversation/search', { params }),

  detail: (id: number): Promise<any> =>
    request.get(`/v2/admin/conversation/${id}/detail`),

  flag: (id: number, note: string): Promise<any> =>
    request.post(`/v2/admin/conversation/${id}/flag`, { note }),

  unflag: (id: number): Promise<any> =>
    request.delete(`/v2/admin/conversation/${id}/flag`),
}
