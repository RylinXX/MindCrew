import request from '@/utils/request'
import { parseDispositionFilename } from '@/utils/download'

/**
 * 检索分析 API · docs/design-retrieval-analytics.md 第 3 节
 *
 * 全部只读，后端要求 ADMIN / AUDITOR。
 */

export type LowQualityType = 'zero' | 'fallback' | 'negative'

export interface RetrievalOverview {
  days: number
  totalAnswers: number
  withLog: number
  hitCount: number
  hitRate: number
  zeroResultCount: number
  zeroResultRate: number
  fallbackCount: number
  fallbackRate: number
  rewriteCount: number
  rewriteRate: number
  avgVector: number
  avgBm25: number
  avgRrf: number
  avgRerank: number
  avgLatencyMs: number
  positiveFeedback: number
  negativeFeedback: number
  satisfactionRate: number
  totalCostCny: number
  avgCostCny: number
}

export interface RetrievalTrendPoint {
  date: string
  questions: number
  hitRate: number
  zeroResultCount: number
  avgLatencyMs: number
  fallbackCount: number
}

export interface RetrievalToolStat {
  tool: string
  count: number
  ratio: number
}

export interface RetrievalKbStat {
  kbId: number
  kbName: string
  count: number
  hitRate: number
  avgRerank: number
}

export interface RetrievalLowQualityRecord {
  messageId: number
  conversationId?: number
  question?: string | null
  rewrittenQuery?: string | null
  vectorResults: number
  bm25Results: number
  rrfCount: number
  rerankTop: number
  fallback: boolean
  feedback: number
  latencyMs: number
  modelName?: string | null
  createTime?: string | null
}

export interface RetrievalLowQualityPage {
  records: RetrievalLowQualityRecord[]
  total: number
  current: number
  size: number
}

export interface RetrievalDetail {
  messageId: number
  question?: string | null
  answer?: string | null
  retrievalLog?: unknown
  reflectionLog?: unknown
  agentTrace?: unknown
  sources?: unknown
  feedback: number
  latencyMs: number
}

export interface BlobFile {
  blob: Blob
  filename?: string
}

async function unwrap<T>(promise: Promise<any>): Promise<T> {
  return (await promise) as T
}

export const retrievalApi = {
  overview: (days: number): Promise<RetrievalOverview> =>
    unwrap<RetrievalOverview>(request.get('/v2/retrieval/overview', { params: { days } })),

  trend: (days: number): Promise<RetrievalTrendPoint[]> =>
    unwrap<RetrievalTrendPoint[]>(request.get('/v2/retrieval/trend', { params: { days } })),

  tools: (days: number): Promise<RetrievalToolStat[]> =>
    unwrap<RetrievalToolStat[]>(request.get('/v2/retrieval/tools', { params: { days } })),

  kbs: (days: number): Promise<RetrievalKbStat[]> =>
    unwrap<RetrievalKbStat[]>(request.get('/v2/retrieval/kbs', { params: { days } })),

  lowQuality: (params: {
    days: number
    current: number
    size: number
    type: LowQualityType
  }): Promise<RetrievalLowQualityPage> =>
    unwrap<RetrievalLowQualityPage>(request.get('/v2/retrieval/low-quality', { params })),

  detail: (messageId: number): Promise<RetrievalDetail> =>
    unwrap<RetrievalDetail>(request.get(`/v2/retrieval/detail/${messageId}`)),

  /**
   * 导出低质量查询 CSV。
   * 该接口返回文件流，全局 request 会按 JSON 解包，所以这里直接用 fetch 拿响应头 + blob。
   */
  exportCsv: async (days: number, type: LowQualityType): Promise<BlobFile> => {
    const query = new URLSearchParams({ days: String(days), type })
    const response = await fetch(`/api/v2/retrieval/low-quality/export?${query.toString()}`, {
      headers: {
        Authorization: `Bearer ${localStorage.getItem('token') || ''}`
      }
    })
    if (!response.ok) {
      let message = `导出失败（HTTP ${response.status}）`
      try {
        const text = await response.text()
        const json = JSON.parse(text)
        if (json?.message) message = json.message
      } catch {
        // ignore
      }
      throw new Error(message)
    }
    const blob = await response.blob()
    return {
      blob,
      filename: parseDispositionFilename(response.headers.get('content-disposition')) || undefined
    }
  }
}
