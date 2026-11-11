import request from '@/utils/request'

export interface LlmProvider {
  id?: number
  name: string
  providerType?: string
  baseUrl: string
  apiKey?: string                // 写入时用；前端展示用 apiKeyMasked
  apiKeyMasked?: string
  apiKeySet?: boolean
  chatModel?: string
  embeddingModel?: string
  embeddingDim?: number
  temperature?: number
  description?: string
  isActive?: number
  enabled?: number
  sortOrder?: number
  lastTestAt?: string
  lastTestOk?: number
  lastTestMsg?: string
}

export const llmProviderApi = {
  list:       (): Promise<any> => request.get('/v2/llm-provider/list'),
  getActive:  (): Promise<any> => request.get('/v2/llm-provider/active'),
  getById:    (id: number): Promise<any> => request.get(`/v2/llm-provider/${id}`),
  create:     (data: LlmProvider): Promise<any> => request.post('/v2/llm-provider', data),
  update:     (id: number, data: LlmProvider): Promise<any> => request.put(`/v2/llm-provider/${id}`, data),
  setActive:  (id: number): Promise<any> => request.post(`/v2/llm-provider/${id}/set-active`),
  delete:     (id: number): Promise<any> => request.delete(`/v2/llm-provider/${id}`),
  /** 测试已保存的 provider（可选 apiKey 覆盖） */
  testById:   (id: number, apiKey?: string): Promise<{ success: boolean; message: string }> =>
                request.post(`/v2/llm-provider/${id}/test`, { apiKey }),
  /** 用未保存的临时配置测试 */
  testRaw:    (data: LlmProvider): Promise<{ success: boolean; message: string }> =>
                request.post('/v2/llm-provider/test', data),
}
