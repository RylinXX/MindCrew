import request from '@/utils/request'

export interface Persona {
  id?: number
  name: string
  description?: string
  systemPrompt: string
  temperature: number
  modelName?: string
  antiSycophancy: number      // 1 or 0
  isDefault?: number
  enabled?: number
  sortOrder?: number
  createTime?: string
  updateTime?: string
}

export interface PreviewResult {
  fullPrompt: string
  promptLength: number
  antiSycophancyEnabled: boolean
}

export const personaApi = {
  list:        ():                     Promise<any> => request.get('/v2/persona/list'),
  getDefault:  ():                     Promise<any> => request.get('/v2/persona/default'),
  getById:     (id: number):           Promise<any> => request.get(`/v2/persona/${id}`),
  create:      (data: Persona):        Promise<any> => request.post('/v2/persona', data),
  update:      (id: number, d: Persona): Promise<any> => request.put(`/v2/persona/${id}`, d),
  setDefault:  (id: number):           Promise<any> => request.post(`/v2/persona/${id}/set-default`),
  delete:      (id: number):           Promise<any> => request.delete(`/v2/persona/${id}`),
  preview:     (data: Persona):        Promise<PreviewResult> => request.post('/v2/persona/preview', data),
}
