import request from '@/utils/request'

export interface GoldenPair {
  id: number
  question: string
  questionNorm?: string
  standardAnswer: string
  sourcesJson?: string
  milvusId?: string
  sourceFeedbackId?: number
  category?: string
  tags?: string
  enabled: number
  hitCount: number
  lastHitAt?: string
  createdBy?: number
  createTime?: string
}

export const goldenPairApi = {
  page: (params: { current?: number; size?: number; keyword?: string; enabled?: number }): Promise<any> =>
    request.get('/v2/golden-pair/page', { params }),

  get: (id: number): Promise<any> => request.get(`/v2/golden-pair/${id}`),

  create: (data: { question: string; answer: string; sourcesJson?: string }): Promise<any> =>
    request.post('/v2/golden-pair', data),

  /** 从反馈生成 */
  fromFeedback: (feedbackId: number, finalAnswer?: string): Promise<any> =>
    request.post(`/v2/golden-pair/from-feedback/${feedbackId}`, { finalAnswer }),

  update: (id: number, data: {
    question?: string
    answer?: string
    enabled?: number
    category?: string
    tags?: string
  }): Promise<any> => request.put(`/v2/golden-pair/${id}`, data),

  delete: (id: number): Promise<any> => request.delete(`/v2/golden-pair/${id}`),

  stats: (): Promise<any> => request.get('/v2/golden-pair/stats'),
}
