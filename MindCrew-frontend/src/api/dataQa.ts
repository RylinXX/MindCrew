import request from '@/utils/request'

/**
 * AI 问数（Data Q&A）API · docs/design-data-qa.md 第 4 节
 *
 * 安全优先：LLM 只做意图映射，后端固定模板参数化查询；前端只负责展示。
 */

export type DataQaChartType = 'line' | 'bar' | 'pie' | 'table'

export interface DataQaMetric {
  key: string
  name: string
  description: string
  chartType: DataQaChartType
  example: string
}

export interface DataQaAskRequest {
  question: string
  days?: number | null
}

export interface DataQaColumn {
  key: string
  label: string
}

export interface DataQaChartSpec {
  type: DataQaChartType
  xKey: string
  yKey: string
  seriesName?: string
}

export interface DataQaInterpreted {
  days: number
  limit: number
  dimension: string
}

export type DataQaRow = Record<string, any>

export interface DataQaAskResult {
  question: string
  metric: string
  metricName: string
  interpreted: DataQaInterpreted
  answer: string
  columns: DataQaColumn[]
  rows: DataQaRow[]
  chart: DataQaChartSpec
  elapsedMs: number
}

async function unwrap<T>(promise: Promise<any>): Promise<T> {
  return (await promise) as T
}

export const dataQaApi = {
  metrics: (): Promise<DataQaMetric[]> =>
    unwrap<DataQaMetric[]>(request.get('/v2/data-qa/metrics')),

  // 需要 LLM 做意图解析，可能比普通接口慢，单独放宽超时
  ask: (data: DataQaAskRequest): Promise<DataQaAskResult> =>
    unwrap<DataQaAskResult>(request.post('/v2/data-qa/ask', data, { timeout: 60000 }))
}
