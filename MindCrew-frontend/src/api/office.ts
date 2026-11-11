import axios from 'axios'
import request from '@/utils/request'
import { parseDispositionFilename } from '@/utils/download'

/**
 * 数字员工交付物 API · docs/design-agent-deliverables.md
 *
 * 生成类接口直接返回文件流，不能走全局 request（它按 Result JSON 解包），
 * 这里单独建一个 blob axios 实例，并从 content-disposition 取文件名。
 */

export interface ContractTemplateField {
  key: string
  label: string
  required: boolean
}

export interface ContractTemplate {
  id: string
  name: string
  description: string
  fields: ContractTemplateField[]
}

export interface PptxRequest {
  title: string
  subtitle?: string
  markdown: string
}

export interface DocxRequest {
  title: string
  markdown: string
}

export interface ContractRequest {
  template: string
  fields: Record<string, string>
}

export interface BlobFile {
  blob: Blob
  filename?: string
}

export type DeliverableFormat = 'pptx' | 'docx' | 'md'

const blobClient = axios.create({
  baseURL: '/api',
  timeout: 120000,
  responseType: 'blob'
})

blobClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

blobClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    let message = error.message || '文件生成失败'
    const data = error.response?.data
    if (data instanceof Blob) {
      try {
        const text = await data.text()
        const json = JSON.parse(text)
        if (json?.message) message = json.message
      } catch {
        // 非 JSON 错误体，保留默认提示
      }
    } else if (data?.message) {
      message = data.message
    }
    return Promise.reject(new Error(message))
  }
)

function toBlobFile(response: { data: Blob; headers: Record<string, any> }): BlobFile {
  return {
    blob: response.data,
    filename: parseDispositionFilename(response.headers?.['content-disposition']) || undefined
  }
}

async function download(promise: Promise<any>): Promise<BlobFile> {
  const response = await promise
  return toBlobFile(response)
}

export const officeApi = {
  listTemplates: async (): Promise<ContractTemplate[]> =>
    (await request.get('/v2/office/templates')) as unknown as ContractTemplate[],

  generatePptx: (data: PptxRequest): Promise<BlobFile> =>
    download(blobClient.post('/v2/office/pptx', data)),

  generateDocx: (data: DocxRequest): Promise<BlobFile> =>
    download(blobClient.post('/v2/office/docx', data)),

  generateContract: (data: ContractRequest): Promise<BlobFile> =>
    download(blobClient.post('/v2/office/contract', data)),

  /** Crew 调研报告交付物（pptx / docx / md） */
  downloadCrewDeliverable: (taskId: number, format: DeliverableFormat): Promise<BlobFile> =>
    download(blobClient.get(`/v2/crew/tasks/${taskId}/deliverable`, { params: { format } }))
}
