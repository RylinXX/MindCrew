<template>
  <div class="voice-call-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">语音通话</h2>
          <span class="title-tag">类豆包 · 直接对话</span>
        </div>
        <p class="page-desc">
          点开始通话后直接说话即可。说完一句 AI 自动回答；AI 说话时你开口会自动打断。
          答案基于你已授权的知识库。
        </p>
      </div>
      <div class="header-actions">
        <el-select v-model="selectedVoiceId" placeholder="选音色" style="width:200px" :disabled="callState !== 'idle'">
          <el-option
            v-for="v in voices"
            :key="v.id"
            :label="v.name"
            :value="v.id"
          >
            <div class="voice-opt">
              <span>{{ v.name }}</span>
              <span class="voice-tag">{{ v.gender === 'female' ? '女' : v.gender === 'male' ? '男' : '' }}</span>
            </div>
          </el-option>
        </el-select>
      </div>
    </header>

    <!-- 通话主面板 -->
    <div class="call-panel">
      <!-- 状态环 -->
      <div class="orb" :class="orbClass">
        <div class="orb-inner">
          <el-icon size="56" color="#fff">
            <PhoneFilled v-if="callState === 'idle' || callState === 'ready'" />
            <Microphone v-else-if="callState === 'listening'" />
            <Loading    v-else-if="callState === 'thinking' || callState === 'connecting'" />
            <Bell       v-else-if="callState === 'speaking'" />
          </el-icon>
        </div>
        <div v-if="callState === 'listening' || callState === 'speaking'" class="orb-wave"></div>
      </div>

      <div class="state-text">{{ stateLabel }}</div>
      <div class="voice-text" v-if="currentVoice">音色：{{ currentVoice.name }}</div>

      <!-- 麦克风电平条 -->
      <div v-if="callState !== 'idle' && callState !== 'connecting'" class="mic-meter">
        <div class="meter-bar" :style="{ width: micLevelPct + '%', background: micLevelColor }"></div>
      </div>

      <!-- 控制按钮 -->
      <div class="ctrl-bar">
        <el-button
          v-if="callState === 'idle'"
          type="primary"
          round
          size="large"
          :icon="PhoneFilled"
          @click="startCall"
        >
          开始通话
        </el-button>
        <template v-else>
          <el-button
            v-if="callState === 'speaking'"
            :icon="VideoPause"
            round
            size="large"
            @click="interruptAi"
          >打断 AI</el-button>
          <el-button
            :icon="CloseBold"
            round
            size="large"
            type="danger"
            @click="endCall"
          >挂断</el-button>
        </template>
      </div>

      <div class="hint" v-if="callState === 'ready' || callState === 'listening'">
        直接说话 → 静默 1 秒视为说完 → AI 自动回答
      </div>
      <div class="hint" v-else-if="callState === 'speaking'">
        AI 说话中 · 你开口会自动打断
      </div>
    </div>

    <!-- 字幕轨 -->
    <div v-if="transcripts.length > 0" class="transcripts" ref="transcriptsRef">
      <div
        v-for="(t, i) in transcripts"
        :key="i"
        class="bubble"
        :class="t.role"
      >
        <div class="bubble-role">{{ t.role === 'user' ? '你' : 'AI' }}</div>
        <div class="bubble-text">{{ t.text }}{{ t.partial ? '…' : '' }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import {
  PhoneFilled, Microphone, Loading, Bell, VideoPause, CloseBold,
} from '@element-plus/icons-vue'
import { voiceApi, type VoicePersona } from '@/api/voice'
import { useUserStore } from '@/stores/user'

type CallState = 'idle' | 'connecting' | 'ready' | 'listening' | 'thinking' | 'speaking'

const userStore = useUserStore()

// ─── 音色 ───
const voices = ref<VoicePersona[]>([])
const selectedVoiceId = ref<number | undefined>(undefined)
const currentVoice = computed(() => voices.value.find(v => v.id === selectedVoiceId.value))

async function loadVoices() {
  try {
    const res: any = await voiceApi.voices()
    voices.value = res?.data ?? res ?? []
    const def = voices.value.find(v => v.isDefault === 1)
    selectedVoiceId.value = def?.id ?? voices.value[0]?.id
  } catch (e: any) {
    ElMessage.error('加载音色失败：' + (e?.message || ''))
  }
}

// ─── 通话状态 ───
const callState = ref<CallState>('idle')
const transcripts = ref<{ role: 'user' | 'ai'; text: string; partial: boolean }[]>([])
const transcriptsRef = ref<HTMLElement | null>(null)
const micLevelPct = ref(0)

const stateLabel = computed(() => ({
  idle: '空闲',
  connecting: '正在连接...',
  ready: '请直接说话',
  listening: '正在聆听...',
  thinking: 'AI 正在思考...',
  speaking: 'AI 正在回答',
}[callState.value]))

const orbClass = computed(() => 'orb-' + callState.value)

const micLevelColor = computed(() => {
  if (micLevelPct.value > 60) return '#10b981'
  if (micLevelPct.value > 20) return '#38bdf8'
  return '#94a3b8'
})

// ─── 音频通路 ───
let ws: WebSocket | null = null
let audioCtx: AudioContext | null = null
let micStream: MediaStream | null = null
let micSrc: MediaStreamAudioSourceNode | null = null
let micNode: AudioWorkletNode | null = null

let playSampleRate = 22050
let playStartTime = 0
let playingChunks = 0
let scheduledNodes: AudioBufferSourceNode[] = []

// VAD 阈值（基于 mic-worklet 输出的 RMS）
const VOICE_RMS_THRESH = 0.025
const SPEAK_DETECT_MS = 250      // 持续超过这个时长视为开始说话
const SILENCE_END_MS = 1000      // 静音超过这个时长视为说完（ASR sentence_end 兜底）
const INTERRUPT_DETECT_MS = 300  // AI 说话时持续 RMS > 阈值多久算用户开口打断

let voiceActiveSince = 0
let silenceSince = 0

// 是否已经主动挂断（避免 onclose 误报）
let userHangup = false

// ─── 启动 ───
async function startCall() {
  if (!selectedVoiceId.value) {
    ElMessage.warning('请选择音色')
    return
  }
  callState.value = 'connecting'
  userHangup = false

  try {
    await ensureAudioContext()
    await openWs()
    await startMicCapture()
  } catch (e: any) {
    ElMessage.error('启动失败：' + (e?.message || ''))
    await endCall()
  }
}

async function ensureAudioContext() {
  if (audioCtx) return
  audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)({
    latencyHint: 'interactive',
  })
  await audioCtx.audioWorklet.addModule('/voice/mic-worklet.js')
}

async function openWs(): Promise<void> {
  return new Promise((resolve, reject) => {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${proto}://${window.location.host}/api/voice-call/ws?token=${encodeURIComponent(userStore.token)}`
    ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'

    let resolved = false

    ws.onopen = () => {
      ws!.send(JSON.stringify({ type: 'config', voiceId: selectedVoiceId.value }))
    }
    ws.onmessage = (ev) => handleWsMessage(ev)
    ws.onerror = (ev) => {
      console.error('[voice-call] ws error', ev)
      if (!resolved) {
        resolved = true
        reject(new Error('ws error'))
      }
    }
    ws.onclose = () => {
      if (!userHangup && callState.value !== 'idle') {
        ElMessage.warning('通话连接已断开')
      }
      callState.value = 'idle'
    }

    const onceReady = (ev: MessageEvent) => {
      if (typeof ev.data === 'string') {
        try {
          const m = JSON.parse(ev.data)
          if (m.type === 'ready') {
            ws!.removeEventListener('message', onceReady)
            if (!resolved) {
              resolved = true
              callState.value = 'ready'
              resolve()
            }
          } else if (m.type === 'error') {
            ws!.removeEventListener('message', onceReady)
            if (!resolved) {
              resolved = true
              reject(new Error(m.message))
            }
          }
        } catch {}
      }
    }
    ws.addEventListener('message', onceReady)
  })
}

function handleWsMessage(ev: MessageEvent) {
  if (typeof ev.data === 'string') {
    try {
      const m = JSON.parse(ev.data)
      onJsonMessage(m)
    } catch {
      // ignore
    }
  } else if (ev.data instanceof ArrayBuffer) {
    onPcmFrame(ev.data)
  }
}

function onJsonMessage(m: any) {
  switch (m.type) {
    case 'asr_partial':
      callState.value = 'listening'
      upsertTranscript('user', m.text, true)
      scrollTranscriptsLater()
      break
    case 'asr_final':
      upsertTranscript('user', m.text, false)
      scrollTranscriptsLater()
      break
    case 'thinking':
      callState.value = 'thinking'
      break
    case 'llm_answer':
      upsertTranscript('ai', m.text, false)
      scrollTranscriptsLater()
      break
    case 'tts_start':
      playSampleRate = m.sampleRate || 22050
      playStartTime = audioCtx!.currentTime
      callState.value = 'speaking'
      break
    case 'tts_end':
      break
    case 'turn_end':
      waitForPlaybackThen(() => {
        if (callState.value !== 'listening') callState.value = 'ready'
      })
      break
    case 'error':
      ElMessage.error(m.message || '通话出错')
      if (ws && ws.readyState === WebSocket.OPEN) callState.value = 'ready'
      else callState.value = 'idle'
      break
    default:
      break
  }
}

function upsertTranscript(role: 'user' | 'ai', text: string, partial: boolean) {
  if (!text) return
  const arr = transcripts.value
  const last = arr[arr.length - 1]
  if (last && last.role === role && last.partial) {
    last.text = text
    last.partial = partial
  } else {
    arr.push({ role, text, partial })
  }
}

function scrollTranscriptsLater() {
  nextTick(() => {
    const el = transcriptsRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onPcmFrame(buf: ArrayBuffer) {
  if (!audioCtx) return
  const i16 = new Int16Array(buf)
  const f32 = new Float32Array(i16.length)
  for (let i = 0; i < i16.length; i++) {
    const s = i16[i] ?? 0
    f32[i] = s / (s < 0 ? 0x8000 : 0x7fff)
  }
  const ab = audioCtx.createBuffer(1, f32.length, playSampleRate)
  ab.getChannelData(0).set(f32)
  const src = audioCtx.createBufferSource()
  src.buffer = ab
  src.connect(audioCtx.destination)
  const startAt = Math.max(audioCtx.currentTime + 0.02, playStartTime)
  src.start(startAt)
  playStartTime = startAt + ab.duration
  playingChunks++
  scheduledNodes.push(src)
  src.onended = () => {
    playingChunks--
    scheduledNodes = scheduledNodes.filter(n => n !== src)
  }
}

function waitForPlaybackThen(cb: () => void) {
  const tick = () => {
    if (playingChunks <= 0 || callState.value === 'idle') cb()
    else setTimeout(tick, 100)
  }
  tick()
}

// ─── 麦克风采集（整通话期间持续）───
async function startMicCapture() {
  if (!audioCtx) return
  if (micNode) return

  micStream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
  })
  micSrc = audioCtx.createMediaStreamSource(micStream)
  micNode = new AudioWorkletNode(audioCtx, 'mic-worklet')
  micNode.port.onmessage = (ev) => {
    const d = ev.data
    if (d.type === 'pcm16') {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(d.buffer)
      }
      handleVad(d.rms)
    } else if (d.type === 'level') {
      handleVad(d.rms)
    }
  }
  micSrc.connect(micNode)
}

function stopMicCapture() {
  if (micNode) { try { micNode.disconnect() } catch {} micNode = null }
  if (micSrc)  { try { micSrc.disconnect() } catch {} micSrc = null }
  if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null }
  micLevelPct.value = 0
}

// ─── VAD ───
function handleVad(rms: number) {
  micLevelPct.value = Math.min(100, Math.round(rms * 700))

  const now = Date.now()
  const voicing = rms > VOICE_RMS_THRESH

  // AI 说话时检测用户开口 → 自动打断
  if (callState.value === 'speaking') {
    if (voicing) {
      if (!voiceActiveSince) voiceActiveSince = now
      else if (now - voiceActiveSince > INTERRUPT_DETECT_MS) {
        console.log('[voice-call] VAD 检测到打断')
        interruptAi()
        voiceActiveSince = 0
      }
    } else {
      voiceActiveSince = 0
    }
    return
  }

  // ready / listening 时纯监听显示（实际识别由服务端 ASR 自动断句驱动）
  if (callState.value === 'ready' || callState.value === 'listening') {
    if (voicing) {
      voiceActiveSince ||= now
      silenceSince = 0
      // 持续 250ms 视为正在说，UI 进入 listening（服务端的 asr_partial 也会触发同样状态）
      if (callState.value === 'ready' && now - voiceActiveSince > SPEAK_DETECT_MS) {
        callState.value = 'listening'
      }
    } else {
      voiceActiveSince = 0
      // 静音 1s 后兜底切回 ready（服务端 asr_final 通常已先一步触发）
      if (callState.value === 'listening') {
        if (!silenceSince) silenceSince = now
        // 不主动 send 任何东西，等服务端 ASR 句末事件
      } else {
        silenceSince = 0
      }
    }
  }
}

// ─── 打断 ───
function interruptAi() {
  for (const n of scheduledNodes) { try { n.stop() } catch {} }
  scheduledNodes = []
  playingChunks = 0
  playStartTime = audioCtx?.currentTime ?? 0
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'cancel' }))
  }
  callState.value = 'ready'
}

// ─── 挂断 ───
async function endCall() {
  userHangup = true
  stopMicCapture()
  for (const n of scheduledNodes) { try { n.stop() } catch {} }
  scheduledNodes = []
  playingChunks = 0
  if (ws) {
    try { ws.close() } catch {}
    ws = null
  }
  if (audioCtx && audioCtx.state !== 'closed') {
    try { await audioCtx.close() } catch {}
    audioCtx = null
  }
  callState.value = 'idle'
  voiceActiveSince = 0
  silenceSince = 0
}

onMounted(loadVoices)
onBeforeUnmount(() => { endCall() })
</script>

<style scoped>
.voice-call-page {
  padding: 28px 32px 48px;
  height: 100%;
  overflow-y: auto;
  background: var(--bg-page);
}

.page-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  gap: 24px; margin-bottom: 28px; flex-wrap: wrap;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #fef3c7, #fde68a); color: #b45309;
}
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }

.voice-opt { display: flex; justify-content: space-between; gap: 12px; }
.voice-tag { color: var(--ink-4); font-size: 11px; }

/* ── call panel ── */
.call-panel {
  max-width: 560px; margin: 28px auto 36px;
  text-align: center;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 48px 36px 36px;
  box-shadow: var(--shadow-sm);
}

.orb {
  position: relative;
  width: 156px; height: 156px;
  margin: 0 auto 24px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #94a3b8 0%, #64748b 100%);
  transition: background 0.3s ease;
  box-shadow: 0 0 0 0 rgba(56, 189, 248, 0);
}
.orb-inner {
  width: 130px; height: 130px;
  border-radius: 50%;
  background: rgba(255,255,255,0.10);
  border: 2px solid rgba(255,255,255,0.25);
  display: flex; align-items: center; justify-content: center;
}
.orb-idle       { background: linear-gradient(135deg, #94a3b8, #64748b); }
.orb-connecting { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.orb-ready      { background: linear-gradient(135deg, #60a5fa, #3b82f6); }
.orb-listening  { background: linear-gradient(135deg, #38bdf8, #0ea5e9); animation: pulse 1.4s infinite; }
.orb-thinking   { background: linear-gradient(135deg, #c084fc, #7c3aed); animation: spin 2s linear infinite; }
.orb-speaking   { background: linear-gradient(135deg, #34d399, #10b981); animation: pulse 1s infinite; }

.orb-wave {
  position: absolute; inset: -16px;
  border-radius: 50%;
  border: 2px solid rgba(56, 189, 248, 0.45);
  animation: wave 1.4s infinite;
}
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(56, 189, 248, 0.4); }
  50% { box-shadow: 0 0 0 18px rgba(56, 189, 248, 0); }
}
@keyframes wave {
  0% { transform: scale(0.95); opacity: 0.8; }
  100% { transform: scale(1.25); opacity: 0; }
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}

.state-text { font-size: 16px; font-weight: 600; color: var(--ink-1); margin-bottom: 4px; }
.voice-text { font-size: 12.5px; color: var(--ink-4); margin-bottom: 18px; }

.mic-meter {
  width: 60%; max-width: 320px; height: 6px;
  margin: 0 auto 22px;
  background: var(--bg-hover);
  border-radius: 3px;
  overflow: hidden;
}
.meter-bar { height: 100%; transition: width 100ms linear, background 200ms ease; }

.ctrl-bar { display: flex; justify-content: center; gap: 12px; flex-wrap: wrap; margin-bottom: 18px; }
.hint { font-size: 12px; color: var(--ink-4); }

/* ── transcripts ── */
.transcripts {
  max-width: 720px; margin: 0 auto;
  display: flex; flex-direction: column; gap: 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px 20px;
  max-height: 420px;
  overflow-y: auto;
}
.bubble { display: flex; gap: 12px; font-size: 13.5px; line-height: 1.65; }
.bubble-role {
  flex-shrink: 0;
  width: 32px; height: 32px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700;
}
.bubble.user .bubble-role { background: rgba(56, 189, 248, 0.15); color: #0284c7; }
.bubble.ai .bubble-role  { background: rgba(124, 58, 237, 0.15); color: #6d28d9; }
.bubble-text { flex: 1; color: var(--ink-1); white-space: pre-wrap; }
</style>
