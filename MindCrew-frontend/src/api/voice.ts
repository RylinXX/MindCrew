import request from '@/utils/request'

export interface VoicePersona {
  id: number
  name: string
  voiceId: string
  provider: string
  model: string
  gender?: string
  language?: string
  description?: string
  tags?: string
  sampleRate?: number
  isDefault: number
  enabled: number
}

export const voiceApi = {
  voices: (): Promise<any> => request.get('/v2/tts/voices'),

  /** 一次性合成 · 返回 audio/wav blob URL */
  synth: async (text: string, voiceId?: number): Promise<string> => {
    const res = await request.post(
      '/v2/tts/synth',
      { text, voiceId },
      { responseType: 'blob' }
    )
    const blob: Blob = (res as any)?.data ?? (res as any)
    return URL.createObjectURL(blob)
  },
}
