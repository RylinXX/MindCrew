import request from '@/utils/request'

export interface Conversation {
  id: number
  title: string
  kbIds?: string
  messageCount: number
  lastActive: string
  createTime: string
}

export interface Message {
  id: number
  conversationId: number
  role: 'user' | 'assistant'
  content: string
  sources: SourceRef[] | null
  agentTrace: any[] | null
  mcpCalls: any[] | null
  reflectionLog: any[] | null
  retrievalLog?: any | null
  feedback: number
  responseTime: number | null
  createTime: string
}

export interface SourceRef {
  index: number
  name: string
  chapter: string
  pageNumber: number
  content: string
  score: number
}

export const chatApi = {
  // 获取会话列表
  listConversations: (params = {}): Promise<any> =>
    request.get('/v2/chat/conversations', { params }),

  // 获取消息历史
  getHistory: (conversationId: number, params = {}): Promise<any> =>
    request.get(`/v2/chat/history/${conversationId}`, { params }),

  // 获取 Agent 推理链
  getAgentTrace: (messageId: number): Promise<any> =>
    request.get(`/v2/chat/agent-trace/${messageId}`),

  // 删除会话
  deleteConversation: (conversationId: number): Promise<void> =>
    request.delete(`/v2/chat/conversations/${conversationId}`),

  // 提交反馈
  submitFeedback: (messageId: number, rating: number): Promise<void> =>
    request.post('/v2/chat/feedback', { messageId, rating }),

  // 导出会话为 Markdown
  exportMarkdown: (conversationId: number): string =>
    `/api/v2/chat/export/${conversationId}`,

  // 获取媒体文件预签名 URL（音视频时间戳溯源用）
  fetchMediaUrl: (objectName: string): Promise<{ url: string; expireSeconds: number }> =>
    request.get('/v2/kb/media-url', { params: { objectName } })
}
