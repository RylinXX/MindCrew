import request from '@/utils/request'
import axios from 'axios'

export interface KnowledgeBase {
  id: number
  name: string
  description: string
  category: string
  fileUrl: string
  fileType: string
  fileSize: number
  chunkCount: number
  status: 'uploading' | 'processing' | 'ready' | 'failed'
  errorMsg: string
  createTime: string
  /** 任务 7 · 可见性 */
  visibility?: 'public' | 'scoped' | 'private'
}

export interface KnowledgeStats {
  total: number
  ready: number
  processing: number
  failed: number
  chunks: number
}

export interface KnowledgeListParams {
  current?: number
  size?: number
  category?: string
  status?: string
}

export interface KbChunk {
  id: number
  kbId: number
  content: string
  chunkIndex: number
  metadata?: string
  vectorId?: string
  createTime?: string
}

export const chunkApi = {
  list: (params: { kbId: number; page?: number; size?: number }): Promise<any> =>
    request.get('/kb/chunks', { params }),

  search: (kbId: number, keyword: string): Promise<any> =>
    request.get('/kb/chunks/search', { params: { kbId, keyword } }),

  update: (id: number, data: { content: string; metadata?: string }): Promise<any> =>
    request.put(`/kb/chunks/${id}`, data),

  remove: (id: number): Promise<any> =>
    request.delete(`/kb/chunks/${id}`),
}

export const knowledgeApi = {
  // 上传文档（带进度回调）
  upload: (
    file: File,
    category: string,
    description: string,
    onProgress?: (percent: number) => void
  ): Promise<number> => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('category', category)
    if (description) formData.append('description', description)

    return axios.post('/api/knowledge/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
        Authorization: `Bearer ${localStorage.getItem('token')}`
      },
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      }
    }).then(res => {
      const { code, message, data } = res.data
      if (code === 200) return data
      throw new Error(message)
    })
  },

  list: (params: KnowledgeListParams): Promise<any> =>
    request.get('/knowledge/list', { params }),

  stats: (params?: { category?: string }): Promise<KnowledgeStats> =>
    request.get('/knowledge/stats', { params }),

  getById: (id: number): Promise<KnowledgeBase> =>
    request.get(`/knowledge/${id}`),

  /** 编辑文档信息（名称 / 分类 / 描述） */
  update: (id: number, data: { name?: string; category?: string; description?: string }): Promise<any> =>
    request.put(`/v2/kb/${id}`, data),

  delete: (id: number): Promise<void> =>
    request.delete(`/knowledge/${id}`),

  reprocess: (id: number): Promise<void> =>
    request.post(`/knowledge/${id}/reprocess`),

  getCategories: (): Promise<string[]> =>
    request.get('/knowledge/categories'),

  /** 任务 7 · 切换可见性 (public / scoped / private) */
  updateVisibility: (id: number, visibility: string): Promise<void> =>
    request.put(`/knowledge/${id}/visibility`, null, { params: { visibility } })
}
