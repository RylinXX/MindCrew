import request from '@/utils/request'

export interface UsageDaily {
  id: number
  userId: number
  statDate: string
  chatCount: number
  inputTokens: number
  outputTokens: number
  embeddingTokens: number
  visionCalls: number
  asrSeconds: number
  costCny: number
  goldenHitCount: number
}

export interface UsageSummary {
  chatCount: number
  inputTokens: number
  outputTokens: number
  embeddingTokens: number
  visionCalls: number
  asrSeconds: number
  goldenHitCount: number
  costCny: number
  dayList: UsageDaily[]
}

export const usageApi = {
  /** 当前用户本月用量 */
  me: (): Promise<any> => request.get('/v2/usage/me'),

  /** 任务 13.6 · 管理员看单用户区间汇总 */
  userSummary: (userId: number, from?: string, to?: string): Promise<any> =>
    request.get(`/v2/usage/user/${userId}/summary`, { params: { from, to } }),

  /** 单用户每日明细（含 dayList） */
  userDays: (userId: number, from?: string, to?: string): Promise<any> =>
    request.get(`/v2/usage/user/${userId}/days`, { params: { from, to } }),

  /** 本月成本 Top N */
  topUsers: (topN: number = 20): Promise<any> =>
    request.get('/v2/usage/top-users', { params: { topN } }),
}
