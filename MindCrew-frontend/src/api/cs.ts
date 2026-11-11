import axios from 'axios'
import request from '@/utils/request'

/**
 * 客服中心 API · docs/design-cs-center.md 4.x
 *
 * - 工作台 / 管理端沿用全局 request（401 自动跳登录）
 * - 公开页（/cs）使用独立 axios 实例：匿名也要能请求，401 不跳转登录页，
 *   由页面自己提示；已登录（SSO 后）会自动带上 localStorage token。
 */

// ─────────────────────────────────────────────
// 公开页专用实例（不劫持 401 到 /login）
// ─────────────────────────────────────────────
const publicClient = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

publicClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

publicClient.interceptors.response.use(
  (response) => {
    const body = response.data ?? {}
    if (body.code === 200) return body.data
    return Promise.reject(new Error(body.message || '请求失败'))
  },
  (error) => {
    const msg = error.response?.data?.message || error.message || '网络错误'
    return Promise.reject(new Error(msg))
  }
)

async function unwrap<T>(promise: Promise<any>): Promise<T> {
  return (await promise) as T
}

// ─────────────────────────────────────────────
// 类型
// ─────────────────────────────────────────────

export type CsSessionStatus = 'bot' | 'human' | 'closed'

export interface CsPublicConfig {
  pageTitle?: string
  pageSubtitle?: string
  welcomeText?: string
  logoUrl?: string
  themeColor?: string
  quickQuestions: string[]
  workHours?: string
  offlineTip?: string
  aiEnabled?: number
  satisfactionEnabled?: number
  showBrand?: number
  greetingAgentName?: string
  ssoEnabled?: number
  ssoMode?: 'internal' | 'external'
  online?: boolean
}

export interface CsSessionCreated {
  sessionId: number
  sessionNo: string
  visitorToken: string
  status: CsSessionStatus
  welcome: string
  quickQuestions: string[]
}

export interface CsPublicMessage {
  id: number
  role: 'user' | 'ai' | 'agent' | 'system'
  content: string
  sources?: string | null
  createTime?: string
}

export interface CsPollResult {
  status: CsSessionStatus
  assigneeName?: string | null
  messages: CsPublicMessage[]
}

export interface CsSendResult {
  messageId: number
  pending: boolean
  status: CsSessionStatus
}

export interface CsRatingResult {
  rating: number
}

export interface CsStatusResult {
  status: CsSessionStatus
}

export interface CsSsoUser {
  id: number
  username: string
  nickname?: string
  role: string
}

export interface CsSsoExchangeResult {
  token: string
  user: CsSsoUser
  appId: string
}

export interface CsSsoMeta {
  enabled: boolean
  appName?: string | null
  appId?: string | null
}

export interface CsSession {
  id: number
  sessionNo: string
  visitorKey?: string
  visitorName?: string
  sourceAppId?: string
  channel: string
  kbIds?: string
  conversationId?: number
  status: CsSessionStatus
  assigneeId?: number
  assigneeName?: string
  messageCount?: number
  lastMessage?: string
  lastActive?: string
  ssoUserId?: number
  rating?: number
  ratingNote?: string
  ip?: string
  userAgent?: string
  createTime?: string
  updateTime?: string
}

export interface CsMessage {
  id: number
  sessionId: number
  role: 'user' | 'ai' | 'agent' | 'system'
  content: string
  sources?: string | null
  retrievalLog?: string | null
  senderId?: number | null
  createTime?: string
}

export interface CsSessionDetail {
  session: CsSession
  assigneeName?: string | null
  messages: CsMessage[]
}

export interface CsSessionPage {
  records: CsSession[]
  total: number
  current: number
  size: number
}

export interface CsStats {
  sessions: number
  aiSessions: number
  handoffCount: number
  handoffRate: number
  avgRating: number
  satisfactionRate: number
  statusDist: Array<{ status: string; count: number }>
  trend: Array<{ date: string; count: number }>
}

export interface CsConfig {
  id?: number
  pageTitle?: string
  pageSubtitle?: string
  welcomeText?: string
  logoUrl?: string
  themeColor?: string
  /** JSON 数组字符串 */
  quickQuestions?: string
  workHours?: string
  offlineTip?: string
  aiEnabled?: number
  /** JSON 数组字符串 */
  handoffKeywords?: string
  /** JSON 数组字符串 */
  kbIds?: string
  ssoEnabled?: number
  ssoMode?: string
  externalCsUrl?: string
  satisfactionEnabled?: number
  showBrand?: number
  greetingAgentName?: string
  createTime?: string
  updateTime?: string
}

export interface CsSsoApp {
  id?: number
  appName: string
  appId?: string
  appSecret?: string
  enabled?: number
  autoCreateUser?: number
  defaultRole?: string
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface CsSsoAppCreated {
  app: CsSsoApp
  appSecret: string
}

export interface CsRotateSecretResult {
  appId: string
  appSecret: string
}

export interface CsDingTalkChannel {
  id?: number
  name?: string
  appKey?: string
  appSecret?: string
  signSecret?: string
  robotCode?: string
  webhookUrl?: string
  enabled?: number
  /** JSON 数组字符串 */
  kbIds?: string
  welcomeText?: string
  createTime?: string
  updateTime?: string
}

export interface CsOverview {
  config: CsConfig
  ssoAppCount: number
  sessionTotal: number
  todaySessions: number
}

export interface CsSessionQuery {
  current?: number
  size?: number
  status?: string
  channel?: string
  keyword?: string
}

// ─────────────────────────────────────────────
// 公开接口 /api/cs/public（匿名 + X-CS-Token）
// ─────────────────────────────────────────────
export const csPublicApi = {
  config: (): Promise<CsPublicConfig> =>
    unwrap<CsPublicConfig>(publicClient.get('/cs/public/config')),

  createSession: (data: { visitorKey?: string; visitorName?: string; channel?: string }): Promise<CsSessionCreated> =>
    unwrap<CsSessionCreated>(publicClient.post('/cs/public/session', data)),

  messages: (sessionNo: string, sinceId: number, token: string): Promise<CsPollResult> =>
    unwrap<CsPollResult>(publicClient.get('/cs/public/messages', {
      params: { sessionNo, sinceId },
      headers: { 'X-CS-Token': token }
    })),

  sendMessage: (sessionNo: string, content: string, token: string): Promise<CsSendResult> =>
    unwrap<CsSendResult>(publicClient.post('/cs/public/message', { sessionNo, content }, {
      headers: { 'X-CS-Token': token }
    })),

  handoff: (sessionNo: string, token: string): Promise<CsStatusResult> =>
    unwrap<CsStatusResult>(publicClient.post('/cs/public/handoff', { sessionNo }, {
      headers: { 'X-CS-Token': token }
    })),

  rating: (sessionNo: string, rating: number, note: string | undefined, token: string): Promise<CsRatingResult> =>
    unwrap<CsRatingResult>(publicClient.post('/cs/public/rating', { sessionNo, rating, note }, {
      headers: { 'X-CS-Token': token }
    })),

  close: (sessionNo: string, token: string): Promise<CsStatusResult> =>
    unwrap<CsStatusResult>(publicClient.post('/cs/public/close', { sessionNo }, {
      headers: { 'X-CS-Token': token }
    }))
}

// ─────────────────────────────────────────────
// SSO /api/cs/sso
// ─────────────────────────────────────────────
export const csSsoApi = {
  exchange: (ticket: string): Promise<CsSsoExchangeResult> =>
    unwrap<CsSsoExchangeResult>(publicClient.post('/cs/sso/exchange', { ticket })),

  meta: (appId?: string): Promise<CsSsoMeta> =>
    unwrap<CsSsoMeta>(publicClient.get('/cs/sso/meta', { params: { appId } }))
}

// ─────────────────────────────────────────────
// 工作台 /api/cs/console（登录即可）
// ─────────────────────────────────────────────
export const csConsoleApi = {
  sessions: (params: CsSessionQuery): Promise<CsSessionPage> =>
    unwrap<CsSessionPage>(request.get('/cs/console/sessions', { params })),

  detail: (id: number): Promise<CsSessionDetail> =>
    unwrap<CsSessionDetail>(request.get(`/cs/console/sessions/${id}`)),

  claim: (id: number): Promise<CsSession> =>
    unwrap<CsSession>(request.post(`/cs/console/sessions/${id}/claim`)),

  reply: (id: number, content: string): Promise<CsMessage> =>
    unwrap<CsMessage>(request.post(`/cs/console/sessions/${id}/reply`, { content })),

  transfer: (id: number, assigneeId: number): Promise<CsSession> =>
    unwrap<CsSession>(request.post(`/cs/console/sessions/${id}/transfer`, { assigneeId })),

  close: (id: number): Promise<CsSession> =>
    unwrap<CsSession>(request.post(`/cs/console/sessions/${id}/close`)),

  stats: (days = 7): Promise<CsStats> =>
    unwrap<CsStats>(request.get('/cs/console/stats', { params: { days } }))
}

// ─────────────────────────────────────────────
// 管理端 /api/cs/admin（仅 ADMIN）
// ─────────────────────────────────────────────
export const csAdminApi = {
  getConfig: (): Promise<CsConfig> =>
    unwrap<CsConfig>(request.get('/cs/admin/config')),

  saveConfig: (config: CsConfig): Promise<CsConfig> =>
    unwrap<CsConfig>(request.put('/cs/admin/config', config)),

  listSsoApps: (): Promise<CsSsoApp[]> =>
    unwrap<CsSsoApp[]>(request.get('/cs/admin/sso-apps')),

  createSsoApp: (data: Partial<CsSsoApp>): Promise<CsSsoAppCreated> =>
    unwrap<CsSsoAppCreated>(request.post('/cs/admin/sso-apps', data)),

  updateSsoApp: (id: number, data: Partial<CsSsoApp>): Promise<CsSsoApp> =>
    unwrap<CsSsoApp>(request.put(`/cs/admin/sso-apps/${id}`, data)),

  deleteSsoApp: (id: number): Promise<void> =>
    unwrap<void>(request.delete(`/cs/admin/sso-apps/${id}`)),

  rotateSecret: (id: number): Promise<CsRotateSecretResult> =>
    unwrap<CsRotateSecretResult>(request.post(`/cs/admin/sso-apps/${id}/rotate-secret`)),

  getDingTalk: (): Promise<CsDingTalkChannel> =>
    unwrap<CsDingTalkChannel>(request.get('/cs/admin/channels/dingtalk')),

  saveDingTalk: (data: CsDingTalkChannel): Promise<CsDingTalkChannel> =>
    unwrap<CsDingTalkChannel>(request.put('/cs/admin/channels/dingtalk', data)),

  testDingTalk: (content: string): Promise<string> =>
    // 走 RAG 生成回答，可能耗时较久
    unwrap<string>(request.post('/cs/admin/channels/dingtalk/test', { content }, { timeout: 60000 })),

  overview: (): Promise<CsOverview> =>
    unwrap<CsOverview>(request.get('/cs/admin/overview'))
}
