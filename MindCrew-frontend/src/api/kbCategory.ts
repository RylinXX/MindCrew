import request from '@/utils/request'

export interface KbCategory {
  id?: number
  code: string
  name: string
  parentId?: number
  description?: string
  icon?: string
  color?: string
  sortOrder?: number
  enabled?: number
}

export const kbCategoryApi = {
  list:   (): Promise<any> => request.get('/v2/kb-category/list'),
  create: (data: KbCategory): Promise<any> => request.post('/v2/kb-category', data),
  update: (id: number, data: KbCategory): Promise<any> => request.put(`/v2/kb-category/${id}`, data),
  delete: (id: number): Promise<any> => request.delete(`/v2/kb-category/${id}`),

  /** 用户改文档分类 → 锁定 */
  setDocumentCategory: (kbId: number, code: string): Promise<any> =>
    request.put(`/v2/kb-category/document/${kbId}/category`, { code }),

  /** 重跑 LLM 分类 */
  reclassify: (kbId: number): Promise<any> =>
    request.post(`/v2/kb-category/document/${kbId}/reclassify`),
}
