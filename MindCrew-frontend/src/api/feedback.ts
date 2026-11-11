import request from '@/utils/request'

export interface QaFeedback {
  id: number
  messageId: number
  conversationId: number
  userId: number
  rating: 'up' | 'down'
  comment?: string
  correctionText?: string
  correctionSources?: string
  status: 'pending' | 'approved' | 'rejected'
  reviewerId?: number
  reviewerNote?: string
  reviewedAt?: string
  goldenPairId?: number
  createTime?: string
}

export const feedbackApi = {
  /** 用户提交反馈 */
  submit: (data: {
    messageId: number
    rating: 'up' | 'down'
    comment?: string
    correctionText?: string
  }): Promise<any> => request.post('/v2/feedback', data),

  /** 审核员列表 */
  page: (params: { current?: number; size?: number; status?: string; rating?: string }): Promise<any> =>
    request.get('/v2/feedback/page', { params }),

  get: (id: number): Promise<any> => request.get(`/v2/feedback/${id}`),

  reject: (id: number, note: string): Promise<any> =>
    request.post(`/v2/feedback/${id}/reject`, { note }),

  count: (): Promise<any> => request.get('/v2/feedback/count'),
}
