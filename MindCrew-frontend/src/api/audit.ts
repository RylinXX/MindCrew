import request from '@/utils/request'

export interface AuditLog {
  id: number
  userId?: number
  username?: string
  action: string
  actionLabel?: string
  targetType?: string
  targetId?: string
  targetName?: string
  status: 'success' | 'failure'
  detailJson?: string
  errorMsg?: string
  ip?: string
  userAgent?: string
  latencyMs?: number
  createdAt: string
}

export interface PiiConfig {
  id?: number
  enabled?: number
  maskPhone?: number
  maskIdCard?: number
  maskBankCard?: number
  maskEmail?: number
  maskAddress?: number
  applyOnUpload?: number
  applyOnResponse?: number
  applyOnAudit?: number
}

export const auditApi = {
  page: (params: {
    current?: number; size?: number
    userId?: number; action?: string; targetType?: string; status?: string
    from?: string; to?: string
  }): Promise<any> => request.get('/v2/audit/page', { params }),

  exportCsvUrl: (params: { userId?: number; action?: string; targetType?: string; status?: string; from?: string; to?: string }): string => {
    const qs = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => { if (v != null && v !== '') qs.append(k, String(v)) })
    return '/api/v2/audit/export.csv?' + qs.toString()
  },
}

export const piiApi = {
  getConfig:    (): Promise<any> => request.get('/v2/pii/config'),
  updateConfig: (data: Partial<PiiConfig>): Promise<any> => request.put('/v2/pii/config', data),
  test:         (text: string): Promise<any> => request.post('/v2/pii/test', { text }),
}
