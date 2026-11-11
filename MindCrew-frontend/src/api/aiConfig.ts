import request from '@/utils/request'

export interface AiConfig {
  id: number
  configKey: string
  configValue: string
  valueType: 'string' | 'integer' | 'float'
  groupName: string
  label: string
  description: string
  defaultValue: string
  minValue?: string
  maxValue?: string
}

export const aiConfigApi = {
  // 查询全部配置（按 groupName 分组）
  listAll: (): Promise<Record<string, AiConfig[]>> =>
    request.get('/admin/ai-config/list'),

  // 批量更新配置
  batchUpdate: (params: Record<string, string>): Promise<void> =>
    request.put('/admin/ai-config/batch', params),

  // 重置指定分组为默认值
  resetGroup: (groupName: string): Promise<void> =>
    request.post(`/admin/ai-config/reset/${groupName}`),

  // 重置全部为默认值
  resetAll: (): Promise<void> =>
    request.post('/admin/ai-config/reset-all'),

  // 获取可选模型列表
  getModels: (): Promise<string[]> =>
    request.get('/admin/ai-config/models')
}
