import request from '@/utils/request'

export interface DashboardData {
  totalUsers: number
  totalConversations: number
  totalMessages: number
  totalKnowledge: number
  periodMessages: number
  dailyTrend: Array<{ date: string; count: number }>
  categoryDistribution: Array<{ name: string; value: number }>
  fallbackStats: { total: number; fallback: number; normal: number; fallbackRate: string }
  userRoleDistribution: Array<{ name: string; value: number }>
  feedbackStats: { useful: number; useless: number; satisfactionRate: string }
  knowledgeStatusStats: Array<{ name: string; value: number }>
  hotKeywords: Array<{ name: string; value: number }>
}

export const statsApi = {
  getDashboard: (timeRange = 'week'): Promise<DashboardData> =>
    request.get('/stats/dashboard', { params: { timeRange } })
}
