import request from '@/utils/request'

export interface CoachSession {
  id: number
  userId: number
  kbIds?: string
  kbScopeLabel?: string
  difficulty: string
  questionTotal: number
  questionDone: number
  correctCount: number
  totalScore: number
  status: 'active' | 'finished' | 'abandoned'
  startAt?: string
  endAt?: string
  createTime?: string
}

export interface CoachQuestion {
  id: number
  sessionId: number
  seq: number
  question: string
  questionType: 'short_answer' | 'multiple_choice' | 'true_false'
  options?: string
  expectedAnswer?: string
  explanation?: string
  sourceChunkId?: number
  sourceKbId?: number
  sourceKbName?: string
  sourceQuote?: string
  difficulty: string
  createTime?: string
}

export interface CoachAnswer {
  id: number
  questionId: number
  sessionId: number
  userId: number
  userAnswer?: string
  score: number
  judgment: 'correct' | 'partial' | 'wrong'
  feedback?: string
  recommendChunkIds?: string
  answerAt?: string
}

export interface UserStats {
  sessionCount: number
  finishedSessionCount: number
  answeredQuestionCount: number
  correctCount: number
  avgScore: number
  accuracy: number
  weakKbs: Array<{
    kbId: number
    kbName: string
    answered: number
    avgScore: number
  }>
}

export interface TeamStatRow {
  userId: number
  sessions: number
  questionDone: number
  avgScore: number
  accuracy: number
}

export const coachApi = {
  // 一次性出全部 N 道题 · LLM 大调用可能跑 30-120 秒，单独拉长 timeout
  startSession: (data: {
    kbIds?: number[]
    difficulty?: 'easy' | 'medium' | 'hard'
    questionTotal?: number
  }): Promise<any> => request.post('/v2/coach/session', data, { timeout: 180_000 }),

  // 纯 DB 查询，瞬时返回
  nextQuestion: (sessionId: number): Promise<any> =>
    request.post(`/v2/coach/session/${sessionId}/next`),

  // 短答题走 LLM 评分，可能 5-20 秒
  submit: (questionId: number, answer: string): Promise<any> =>
    request.post(`/v2/coach/question/${questionId}/submit`, { answer }, { timeout: 60_000 }),

  endSession: (sessionId: number): Promise<any> =>
    request.post(`/v2/coach/session/${sessionId}/end`),

  getSession: (sessionId: number): Promise<any> =>
    request.get(`/v2/coach/session/${sessionId}`),

  sessionDetail: (sessionId: number): Promise<any> =>
    request.get(`/v2/coach/session/${sessionId}/questions`),

  mySessions: (params: { current?: number; size?: number }): Promise<any> =>
    request.get('/v2/coach/sessions', { params }),

  myStats: (): Promise<any> => request.get('/v2/coach/my-stats'),

  teamStats: (recentDays = 30): Promise<any> =>
    request.get('/v2/coach/team-stats', { params: { recentDays } }),
}
