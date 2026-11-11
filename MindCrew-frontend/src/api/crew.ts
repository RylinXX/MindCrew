import request from '@/utils/request'

// ─────────────────────────────────────────────
// 类型
// ─────────────────────────────────────────────
export interface PlanItem {
  index: number
  title: string
  query: string
  section: string
}

export interface SourceRef {
  docName: string
  chapter?: string
  pageNumber?: number
  excerpt?: string
  score?: number
}

export interface Finding {
  planIndex: number
  title: string
  section: string
  summary: string
  sources: SourceRef[]
}

export interface ReviewResult {
  score: number
  passed: boolean
  factuality: number
  completeness: number
  citationCoverage: number
  issues: string[]
  suggestion: string
}

export interface AgentTask {
  id: number
  userId: number
  conversationId?: number
  parentTaskId?: number              // Fork 的原任务 ID
  forkedFromStep?: number            // Fork 起点步骤
  forkEditSummary?: string           // 用户编辑说明
  query: string
  kbIds?: string
  status: string                     // PENDING | PLANNING | RESEARCHING | WRITING | REVIEWING | REVISING | COMPLETED | FAILED
  currentRole?: string
  planJson?: string
  finalReport?: string
  reviewScore?: number
  revisionCount: number
  totalSteps: number
  totalTokens: number
  elapsedMs: number
  errorMsg?: string
  startTime?: string
  endTime?: string
  createTime: string
}

export interface AgentStep {
  id: number
  taskId: number
  stepIndex: number
  agentRole: string                  // PLANNER | RESEARCHER | WRITER | CRITIC
  stepName: string
  subtask?: string
  input?: string
  output?: string
  status: string                     // RUNNING | DONE | FAILED | SKIPPED
  elapsedMs: number
  tokens: number
  errorMsg?: string
  createTime: string
}

export interface CrewEvent {
  type: string
  role?: string
  stepIndex?: number
  progress?: number
  data?: Record<string, any>
}

// ─────────────────────────────────────────────
// REST API
// ─────────────────────────────────────────────
export const crewApi = {
  createTask(query: string, opts: { conversationId?: number; kbIds?: string } = {}) {
    return request.post<{ taskId: number; status: string }>('/v2/crew/tasks', {
      query,
      conversationId: opts.conversationId,
      kbIds: opts.kbIds,
    })
  },

  getTask(taskId: number) {
    return request.get<{ task: AgentTask; steps: AgentStep[] }>(`/v2/crew/tasks/${taskId}`)
  },

  listTasks(params: { current?: number; size?: number; status?: string } = {}) {
    return request.get('/v2/crew/tasks', { params })
  },

  deleteTask(taskId: number) {
    return request.delete(`/v2/crew/tasks/${taskId}`)
  },

  /**
   * 启动并订阅 SSE 流。
   * 返回 EventSource 实例，调用方需要：
   *   - 添加 listener: source.addEventListener('task.start', ...)
   *   - 完成后 source.close()
   *
   * EventSource 不支持 header，token 通过 query 传递（同 chat/stream）
   */
  streamTask(taskId: number, token: string): EventSource {
    const url = `/api/v2/crew/tasks/${taskId}/stream?token=${encodeURIComponent(token)}`
    return new EventSource(url)
  },

  // ─────────────────────────────────────────────
  // Time-Travel · Fork
  // ─────────────────────────────────────────────
  /**
   * 创建一个 Fork 任务（不立即执行）。
   * 返回新 taskId 后，调用方应跳到回放页并用 streamFork 启动。
   */
  forkTask(originalTaskId: number, body: {
    fromStepIndex: number
    editedOutput: string
    editSummary?: string
  }) {
    return request.post<{ taskId: number; parentTaskId: number; forkedFromStep: number }>(
      `/v2/crew/tasks/${originalTaskId}/fork`,
      body,
    )
  },

  /** 启动并订阅 Fork 任务的 SSE 流 */
  streamFork(forkId: number, token: string): EventSource {
    const url = `/api/v2/crew/tasks/${forkId}/fork-stream?token=${encodeURIComponent(token)}`
    return new EventSource(url)
  },
}
