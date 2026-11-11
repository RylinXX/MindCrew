<template>
  <div class="cs-portal" :style="themeStyle">
    <!-- 顶部栏 -->
    <header class="cs-header">
      <div class="cs-brand">
        <div class="cs-logo">
          <img v-if="config.logoUrl" :src="config.logoUrl" alt="logo" />
          <span v-else class="cs-logo-text">{{ brandInitial }}</span>
        </div>
        <div class="cs-brand-text">
          <h1 class="cs-title">{{ config.pageTitle || 'MindCrew 智能客服' }}</h1>
          <p class="cs-subtitle">{{ config.pageSubtitle || '7×24 小时智能问答 · 可随时转人工' }}</p>
        </div>
      </div>
      <div class="cs-header-right">
        <span class="cs-online" :class="{ offline: !config.online }">
          <span class="dot"></span>
          {{ config.online ? '在线' : '离线' }}
          <template v-if="config.workHours"> · {{ config.workHours }}</template>
        </span>
        <button class="cs-ghost-btn" @click="handleHandoff" :disabled="status === 'closed' || status === 'human'">
          转人工
        </button>
        <button class="cs-ghost-btn danger" @click="handleClose" :disabled="status === 'closed'">
          结束会话
        </button>
      </div>
    </header>

    <!-- 错误提示条 -->
    <div v-if="errorBanner" class="cs-banner error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ errorBanner }}</span>
      <button class="banner-close" @click="errorBanner = ''">×</button>
    </div>

    <!-- 主区域 -->
    <main class="cs-main">
      <section class="cs-chat-card" v-loading="booting">
        <!-- 消息区 -->
        <div class="cs-messages" ref="messagesRef">
          <div class="cs-chat-start" v-if="messages.length === 0 && !booting">
            <div class="cs-avatar big">{{ brandInitial }}</div>
            <p class="cs-welcome">{{ config.welcomeText || ('你好，我是' + (config.greetingAgentName || '智能客服') + '，有什么可以帮你？') }}</p>
          </div>

          <div
            v-for="msg in messages"
            :key="msg.id ?? msg.tempId"
            class="cs-msg"
            :class="msg.role === 'user' ? 'from-user' : msg.role === 'system' ? 'is-system' : 'from-staff'"
          >
            <template v-if="msg.role === 'system'">
              <span class="cs-system-text">{{ msg.content }}</span>
            </template>
            <template v-else>
              <div v-if="msg.role !== 'user'" class="cs-avatar">{{ staffInitial }}</div>
              <div class="cs-bubble-wrap">
                <div class="cs-bubble" v-if="msg.role === 'user'">{{ msg.content }}</div>
                <div v-else class="cs-bubble md-body" v-html="msg.html"></div>

                <!-- 答案溯源 -->
                <div v-if="msg.sources.length" class="cs-sources">
                  <button class="cs-sources-toggle" @click="msg.showSources = !msg.showSources">
                    <el-icon :size="12"><Document /></el-icon>
                    参考来源 {{ msg.sources.length }} 条
                    <el-icon :size="10" class="caret" :class="{ open: msg.showSources }"><ArrowDown /></el-icon>
                  </button>
                  <div v-if="msg.showSources" class="cs-source-list">
                    <div v-for="(src, i) in msg.sources" :key="i" class="cs-source-item">
                      <span class="src-idx">[{{ src.index ?? i + 1 }}]</span>
                      <span class="src-name">{{ src.name || src.title || '文档片段' }}</span>
                      <span v-if="src.score" class="src-score">相似度 {{ Math.round(src.score * 100) }}%</span>
                      <p v-if="src.content" class="src-excerpt">{{ src.content }}</p>
                    </div>
                  </div>
                </div>
                <div class="cs-msg-time">{{ formatTime(msg.createTime) }}</div>
              </div>
              <div v-if="msg.role === 'user'" class="cs-avatar user-avatar">{{ visitorInitial }}</div>
            </template>
          </div>

          <div v-if="typing" class="cs-msg from-staff">
            <div class="cs-avatar">{{ staffInitial }}</div>
            <div class="cs-bubble typing"><span></span><span></span><span></span></div>
          </div>
          <div ref="bottomRef" class="cs-bottom-anchor"></div>
        </div>

        <!-- 快捷问题 -->
        <div v-if="quickQuestions.length && messages.length <= 1 && status !== 'closed'" class="cs-quick-row">
          <button
            v-for="q in quickQuestions"
            :key="q"
            class="cs-quick-chip"
            :disabled="sending"
            @click="sendMessage(q)"
          >{{ q }}</button>
        </div>

        <!-- 输入区 -->
        <footer class="cs-composer" v-if="status !== 'closed'">
          <textarea
            v-model="draft"
            class="cs-input"
            rows="2"
            :placeholder="composerPlaceholder"
            @keydown.enter.exact.prevent="sendMessage()"
            @keydown.shift.enter.stop
          ></textarea>
          <button class="cs-send-btn" :disabled="!draft.trim() || sending" @click="sendMessage()">
            <el-icon v-if="!sending" :size="16"><Promotion /></el-icon>
            <el-icon v-else :size="16" class="is-loading"><Loading /></el-icon>
          </button>
        </footer>
        <footer class="cs-composer closed" v-else>
          <span class="closed-text">会话已结束，感谢你的咨询</span>
          <button class="cs-primary-btn" @click="restartSession">重新开始</button>
        </footer>
      </section>

      <!-- 侧栏 -->
      <aside class="cs-side">
        <div class="cs-side-card">
          <h3 class="side-title">服务时间</h3>
          <p class="side-value">{{ config.workHours || '9:00-18:00' }}</p>
          <p class="side-tip" v-if="!config.online && config.offlineTip">{{ config.offlineTip }}</p>
        </div>
        <div class="cs-side-card">
          <h3 class="side-title">智能坐席</h3>
          <p class="side-value">{{ config.greetingAgentName || '智能客服' }}</p>
          <p class="side-tip">AI 会先尝试回答，处理不了可以点右上角「转人工」。</p>
        </div>
        <div class="cs-side-card" v-if="status === 'human'">
          <h3 class="side-title">人工客服</h3>
          <p class="side-value">{{ assigneeName || '已转人工，请稍候' }}</p>
          <p class="side-tip">已进入人工会话，客服回复会实时出现在左侧。</p>
        </div>
      </aside>
    </main>

    <footer class="cs-footer" v-if="config.showBrand">
      <span>Powered by MindCrew · Agentic RAG</span>
    </footer>

    <!-- 满意度 -->
    <el-dialog v-model="ratingVisible" title="本次服务满意吗？" width="420px" :close-on-click-modal="false">
      <div class="rating-body">
        <p class="rating-hint">你的评价会帮助我们做得更好</p>
        <el-rate v-model="ratingValue" :max="5" show-text :texts="ratingTexts" />
        <el-input v-model="ratingNote" type="textarea" :rows="3" maxlength="200" placeholder="还有什么想告诉我们的？（选填）" />
      </div>
      <template #footer>
        <el-button @click="ratingVisible = false">跳过</el-button>
        <el-button type="primary" :loading="ratingSubmitting" @click="submitRating">提交评价</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Document, Loading, Promotion, WarningFilled } from '@element-plus/icons-vue'
import { marked } from 'marked'
import { csPublicApi, csSsoApi, type CsPublicConfig, type CsPublicMessage, type CsSessionStatus } from '@/api/cs'

marked.setOptions({ breaks: true, gfm: true })

interface SourceItem {
  index?: number
  name?: string
  title?: string
  content?: string
  score?: number
  chapter?: string
  pageNumber?: number
}

interface PortalMessage {
  id?: number
  tempId?: string
  role: 'user' | 'ai' | 'agent' | 'system'
  content: string
  sources: SourceItem[]
  html?: string
  showSources?: boolean
  createTime?: string
}

const route = useRoute()
const router = useRouter()

const config = ref<CsPublicConfig>({ quickQuestions: [] })
const booting = ref(true)
const errorBanner = ref('')

const sessionNo = ref('')
const visitorToken = ref('')
const status = ref<CsSessionStatus>('bot')
const assigneeName = ref('')
const messages = ref<PortalMessage[]>([])
const quickQuestions = ref<string[]>([])
const draft = ref('')
const sending = ref(false)
const typing = ref(false)

const ratingVisible = ref(false)
const ratingValue = ref(5)
const ratingNote = ref('')
const ratingSubmitting = ref(false)
const ratingTexts = ['很不满意', '不满意', '一般', '满意', '非常满意']

const messagesRef = ref<HTMLElement | null>(null)
const bottomRef = ref<HTMLElement | null>(null)

let lastMessageId = 0
let pollTimer: number | undefined
let slowPollTimer: number | undefined
let pollDeadline = 0

const LS_VISITOR_KEY = 'mindcrew_cs_visitor_key'
const SS_SESSION_NO = 'mindcrew_cs_session_no'
const SS_VISITOR_TOKEN = 'mindcrew_cs_visitor_token'

const brandInitial = computed(() => {
  const title = (config.value.pageTitle || 'MindCrew').trim()
  return title ? title.slice(0, 1) : 'M'
})
const staffInitial = computed(() => (config.value.greetingAgentName || '客服').slice(0, 1))
const visitorInitial = computed(() => {
  // 访客没有账号信息，用一个中性字符占位
  return '我'
})
const composerPlaceholder = computed(() =>
  status.value === 'human'
    ? '人工客服已接入，输入你的问题…'
    : '输入你的问题，Enter 发送，Shift+Enter 换行'
)

const themeStyle = computed(() => {
  const color = config.value.themeColor || '#3D5AFE'
  const rgb = hexToRgb(color)
  return {
    '--cs-theme': color,
    '--cs-theme-soft': rgb ? `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0.12)` : 'rgba(61, 90, 254, 0.12)',
    '--cs-theme-strong': rgb ? `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0.22)` : 'rgba(61, 90, 254, 0.22)'
  }
})

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const value = hex.replace('#', '').trim()
  const normalized = value.length === 3 ? value.split('').map(c => c + c).join('') : value
  if (!/^[0-9a-fA-F]{6}$/.test(normalized)) return null
  return {
    r: parseInt(normalized.slice(0, 2), 16),
    g: parseInt(normalized.slice(2, 4), 16),
    b: parseInt(normalized.slice(4, 6), 16)
  }
}

const formatTime = (time?: string) => {
  if (!time) return ''
  const date = new Date(time.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function safeParseSources(raw?: string | null): SourceItem[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) return parsed as SourceItem[]
    return []
  } catch {
    return []
  }
}

function toPortalMessage(msg: CsPublicMessage): PortalMessage {
  return {
    id: msg.id,
    role: msg.role,
    content: msg.content,
    sources: safeParseSources(msg.sources),
    html: msg.role === 'user' ? undefined : (marked.parse(msg.content || '') as string),
    showSources: false,
    createTime: msg.createTime
  }
}

function appendMessages(list: CsPublicMessage[]) {
  if (!list.length) return
  for (const raw of list) {
    if (raw.id <= lastMessageId) continue
    lastMessageId = raw.id
    // 本地乐观插入的 user 消息会用 poll 回来的同一条替换，避免重复显示
    if (raw.role === 'user') {
      const localIndex = messages.value.findIndex(
        m => m.tempId?.startsWith('local_') && m.content === raw.content
      )
      const local = localIndex >= 0 ? messages.value[localIndex] : undefined
      if (local) {
        local.id = raw.id
        local.tempId = undefined
        local.createTime = raw.createTime
        continue
      }
    }
    messages.value.push(toPortalMessage(raw))
  }
  scrollToBottom()
}

function scrollToBottom() {
  nextTick(() => {
    bottomRef.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  })
}

// ─────────────────────────────────────────────
// 初始化
// ─────────────────────────────────────────────
onMounted(async () => {
  await handleSsoCallback()
  await loadConfig()
  await ensureSession()
  booting.value = false
  scrollToBottom()
})

onUnmounted(() => {
  stopPolling()
  stopSlowPolling()
})

async function handleSsoCallback() {
  const ticket = route.query.ticket
  const csError = route.query.cs_error

  if (typeof csError === 'string' && csError) {
    const map: Record<string, string> = {
      invalid_sign: '单点登录校验失败，请从客户系统重新进入',
      sso_disabled: '当前未开放单点登录',
      app_disabled: '对接应用已停用'
    }
    errorBanner.value = map[csError] || `单点登录失败（${csError}）`
    await router.replace({ path: '/cs' })
  }

  if (typeof ticket === 'string' && ticket) {
    try {
      const result = await csSsoApi.exchange(ticket)
      if (result?.token) {
        localStorage.setItem('token', result.token)
      }
      // 清掉 URL 上的一次性 ticket
      await router.replace({ path: '/cs' })
      ElMessage.success('已通过单点登录进入客服中心')
    } catch (e: any) {
      errorBanner.value = '单点登录换取登录态失败：' + (e?.message || '请重新进入')
      await router.replace({ path: '/cs' })
    }
  }
}

async function loadConfig() {
  try {
    config.value = await csPublicApi.config()
    quickQuestions.value = config.value.quickQuestions || []
  } catch (e: any) {
    errorBanner.value = '客服配置加载失败：' + (e?.message || '请稍后重试')
  }
}

function getVisitorKey(): string {
  let key = localStorage.getItem(LS_VISITOR_KEY)
  if (!key) {
    key = 'guest_' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36)
    localStorage.setItem(LS_VISITOR_KEY, key)
  }
  return key
}

async function createNewSession() {
  const created = await csPublicApi.createSession({
    visitorKey: getVisitorKey(),
    channel: 'web'
  })
  sessionNo.value = created.sessionNo
  visitorToken.value = created.visitorToken
  status.value = created.status
  assigneeName.value = ''
  sessionStorage.setItem(SS_SESSION_NO, created.sessionNo)
  sessionStorage.setItem(SS_VISITOR_TOKEN, created.visitorToken)

  quickQuestions.value = created.quickQuestions?.length ? created.quickQuestions : quickQuestions.value
  lastMessageId = 0

  // 首屏欢迎语（AI 坐席）
  const welcome = created.welcome || config.value.welcomeText
  messages.value = welcome
    ? [{
        tempId: 'welcome',
        role: 'ai',
        content: welcome,
        sources: [],
        html: marked.parse(welcome) as string,
        showSources: false,
        createTime: new Date().toISOString()
      }]
    : []
}

async function ensureSession() {
  const savedNo = sessionStorage.getItem(SS_SESSION_NO)
  const savedToken = sessionStorage.getItem(SS_VISITOR_TOKEN)
  if (savedNo && savedToken) {
    try {
      const result = await csPublicApi.messages(savedNo, 0, savedToken)
      sessionNo.value = savedNo
      visitorToken.value = savedToken
      status.value = result.status
      assigneeName.value = result.assigneeName || ''
      messages.value = []
      lastMessageId = 0
      appendMessages(result.messages || [])
      if (!messages.value.length) {
        const welcome = config.value.welcomeText || `你好，我是${config.value.greetingAgentName || '智能客服'}，有什么可以帮你？`
        messages.value = [{
          tempId: 'welcome',
          role: 'ai',
          content: welcome,
          sources: [],
          html: marked.parse(welcome) as string,
          showSources: false
        }]
      }
      if (status.value === 'human') startSlowPolling()
      return
    } catch {
      // 访客凭证过期 / 会话不存在 → 重新建会话
      sessionStorage.removeItem(SS_SESSION_NO)
      sessionStorage.removeItem(SS_VISITOR_TOKEN)
    }
  }
  try {
    await createNewSession()
  } catch (e: any) {
    errorBanner.value = '创建会话失败：' + (e?.message || '请刷新重试')
  }
}

async function restartSession() {
  stopPolling()
  stopSlowPolling()
  sessionStorage.removeItem(SS_SESSION_NO)
  sessionStorage.removeItem(SS_VISITOR_TOKEN)
  booting.value = true
  try {
    await createNewSession()
  } catch (e: any) {
    errorBanner.value = '创建会话失败：' + (e?.message || '请稍后重试')
  } finally {
    booting.value = false
    scrollToBottom()
  }
}

// ─────────────────────────────────────────────
// 发送 / 轮询
// ─────────────────────────────────────────────
async function sendMessage(preset?: string) {
  const content = (preset ?? draft.value).trim()
  if (!content || sending.value || status.value === 'closed') return
  if (!sessionNo.value || !visitorToken.value) {
    errorBanner.value = '会话尚未就绪，请刷新重试'
    return
  }

  draft.value = ''
  sending.value = true
  const tempId = 'local_' + Date.now()
  messages.value.push({
    tempId,
    role: 'user',
    content,
    sources: [],
    createTime: new Date().toISOString()
  })
  scrollToBottom()

  try {
    const result = await csPublicApi.sendMessage(sessionNo.value, content, visitorToken.value)
    status.value = result.status

    if (!result.pending) {
      // 已直接转人工 / 会话处于人工状态：稍后拉取 system 消息
      await pollOnce()
      if (status.value === 'human') {
        startSlowPolling()
        ElMessage.info('已为你转接人工客服')
      }
      return
    }

    startPolling()
  } catch (e: any) {
    // 移除发送失败的本条消息
    messages.value = messages.value.filter(m => m.tempId !== tempId)
    errorBanner.value = '发送失败：' + (e?.message || '请稍后重试')
  } finally {
    sending.value = false
  }
}

function startPolling() {
  stopPolling()
  typing.value = true
  pollDeadline = Date.now() + 90_000
  pollTimer = window.setInterval(async () => {
    const hasNew = await pollOnce()
    if (hasNew || status.value === 'closed') {
      stopPolling()
      typing.value = false
      if (status.value === 'human') startSlowPolling()
      return
    }
    if (Date.now() > pollDeadline) {
      stopPolling()
      typing.value = false
      errorBanner.value = '智能客服响应超时，可点右上角「转人工」继续'
    }
  }, 1200)
}


/** 拉取一次新消息，返回是否有新消息 */
async function pollOnce(): Promise<boolean> {
  if (!sessionNo.value || !visitorToken.value) return false
  try {
    const result = await csPublicApi.messages(sessionNo.value, lastMessageId, visitorToken.value)
    status.value = result.status
    assigneeName.value = result.assigneeName || ''
    if (result.messages?.length) {
      appendMessages(result.messages)
      return true
    }
  } catch {
    // 轮询失败静默重试，超时由上层处理
  }
  return false
}

function stopPolling() {
  if (pollTimer !== undefined) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function startSlowPolling() {
  stopSlowPolling()
  slowPollTimer = window.setInterval(async () => {
    const hasNew = await pollOnce()
    if (status.value === 'closed') {
      stopSlowPolling()
    }
    if (hasNew) typing.value = false
  }, 3000)
}

function stopSlowPolling() {
  if (slowPollTimer !== undefined) {
    clearInterval(slowPollTimer)
    slowPollTimer = undefined
  }
}

// ─────────────────────────────────────────────
// 转人工 / 关闭 / 评分
// ─────────────────────────────────────────────
async function handleHandoff() {
  if (!sessionNo.value || !visitorToken.value || status.value === 'closed') return
  try {
    const result = await csPublicApi.handoff(sessionNo.value, visitorToken.value)
    status.value = result.status
    startSlowPolling()
    await pollOnce()
    ElMessage.success('已转接人工客服')
  } catch (e: any) {
    errorBanner.value = '转人工失败：' + (e?.message || '请重试')
  }
}

async function handleClose() {
  if (!sessionNo.value || !visitorToken.value || status.value === 'closed') return
  try {
    const result = await csPublicApi.close(sessionNo.value, visitorToken.value)
    status.value = result.status
    stopPolling()
    stopSlowPolling()
    ElMessage.success('会话已结束')
    if (config.value.satisfactionEnabled === 1) {
      ratingValue.value = 5
      ratingNote.value = ''
      ratingVisible.value = true
    }
  } catch (e: any) {
    errorBanner.value = '结束会话失败：' + (e?.message || '请重试')
  }
}

async function submitRating() {
  if (!sessionNo.value || !visitorToken.value) return
  ratingSubmitting.value = true
  try {
    await csPublicApi.rating(sessionNo.value, ratingValue.value, ratingNote.value || undefined, visitorToken.value)
    ratingVisible.value = false
    ElMessage.success('感谢你的评价')
  } catch (e: any) {
    errorBanner.value = '评价提交失败：' + (e?.message || '请重试')
  } finally {
    ratingSubmitting.value = false
  }
}
</script>

<style scoped>
/* /cs 是公开页：独立全屏布局，不套 MainLayout */
.cs-portal {
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(900px 460px at 12% -8%, var(--cs-theme-soft), transparent 60%),
    radial-gradient(700px 420px at 108% 108%, var(--cs-theme-soft), transparent 62%),
    var(--bg-page);
  font-family: 'Plus Jakarta Sans', 'Noto Sans SC', 'PingFang SC', sans-serif;
  color: var(--ink-1);
  overflow: hidden;
}

.cs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 26px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
  flex-wrap: wrap;
}
.cs-brand { display: flex; align-items: center; gap: 13px; min-width: 0; }
.cs-logo {
  width: 44px; height: 44px;
  border-radius: 13px;
  background: linear-gradient(135deg, var(--cs-theme), var(--cs-theme-strong));
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 800; font-size: 19px;
  overflow: hidden; flex-shrink: 0;
  box-shadow: 0 6px 18px var(--cs-theme-soft);
}
.cs-logo img { width: 100%; height: 100%; object-fit: cover; }
.cs-brand-text { min-width: 0; }
.cs-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 19px; font-weight: 800; color: var(--ink-1);
  letter-spacing: -0.02em;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.cs-subtitle {
  font-size: 12.5px; color: var(--ink-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.cs-header-right { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.cs-online {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--ink-3);
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  padding: 5px 11px; border-radius: 999px;
}
.cs-online .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--success); }
.cs-online.offline .dot { background: var(--ink-4); }
.cs-ghost-btn {
  height: 34px; padding: 0 14px;
  border: 1px solid var(--line);
  background: var(--bg-surface);
  color: var(--ink-2);
  border-radius: 10px;
  font-size: 13px;
  cursor: pointer;
  transition: all 180ms ease;
}
.cs-ghost-btn:hover:not(:disabled) {
  border-color: var(--cs-theme);
  color: var(--cs-theme);
  background: var(--cs-theme-soft);
}
.cs-ghost-btn.danger:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger);
  background: var(--danger-soft);
}
.cs-ghost-btn:disabled { opacity: 0.45; cursor: not-allowed; }

.cs-banner {
  display: flex; align-items: center; gap: 8px;
  padding: 9px 26px;
  font-size: 13px;
  background: var(--warning-soft);
  color: var(--warning-ink);
  border-bottom: 1px solid #FDE68A;
}
.cs-banner.error { background: var(--danger-soft); color: var(--danger-ink); border-bottom-color: #FECACA; }
.banner-close { margin-left: auto; font-size: 18px; line-height: 1; background: none; border: none; color: inherit; cursor: pointer; }

.cs-main {
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: 1180px;
  margin: 0 auto;
  padding: 20px 26px 12px;
  display: flex;
  gap: 18px;
  overflow: hidden;
}

.cs-chat-card {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 18px;
  box-shadow: var(--shadow-md);
  overflow: hidden;
}
.cs-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px 24px 8px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.cs-chat-start { text-align: center; padding: 34px 12px 12px; }
.cs-avatar {
  width: 34px; height: 34px; border-radius: 50%;
  background: linear-gradient(135deg, var(--cs-theme), var(--cs-theme-strong));
  color: #fff; font-size: 14px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.cs-avatar.big { width: 58px; height: 58px; font-size: 23px; margin: 0 auto 14px; }
.cs-avatar.user-avatar { background: linear-gradient(135deg, #FF8A65, #F43F5E); }
.cs-welcome { font-size: 14px; color: var(--ink-2); max-width: 520px; margin: 0 auto; line-height: 1.7; }

.cs-msg { display: flex; gap: 10px; align-items: flex-start; }
.cs-msg.from-user { justify-content: flex-end; }
.cs-msg.from-user .cs-bubble-wrap { align-items: flex-end; }
.cs-msg.is-system { justify-content: center; }
.cs-system-text {
  font-size: 12px; color: var(--ink-3);
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  padding: 5px 12px; border-radius: 999px;
}
.cs-bubble-wrap { display: flex; flex-direction: column; gap: 6px; max-width: min(78%, 640px); min-width: 0; }
.cs-bubble {
  padding: 11px 15px;
  border-radius: 14px;
  border-top-left-radius: 4px;
  background: var(--bg-subtle);
  color: var(--ink-1);
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}
.cs-msg.from-user .cs-bubble {
  background: var(--cs-theme);
  color: #fff;
  border-top-left-radius: 14px;
  border-top-right-radius: 4px;
  white-space: pre-wrap;
}
.cs-msg-time { font-size: 10.5px; color: var(--ink-4); padding: 0 4px; }
.cs-msg.from-user .cs-msg-time { text-align: right; }

.cs-sources { margin-top: 2px; }
.cs-sources-toggle {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 12px; color: var(--cs-theme);
  background: var(--cs-theme-soft);
  border: 1px solid transparent;
  padding: 4px 10px; border-radius: 999px;
  cursor: pointer;
}
.cs-sources-toggle:hover { border-color: var(--cs-theme-strong); }
.caret { transition: transform 160ms ease; }
.caret.open { transform: rotate(180deg); }
.cs-source-list { margin-top: 7px; display: flex; flex-direction: column; gap: 6px; }
.cs-source-item {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 11px;
  font-size: 12px;
}
.src-idx { color: var(--cs-theme); font-weight: 700; margin-right: 5px; }
.src-name { color: var(--ink-1); font-weight: 600; }
.src-score { color: var(--ink-3); margin-left: 8px; }
.src-excerpt { color: var(--ink-3); margin-top: 4px; line-height: 1.6; }

.typing { display: inline-flex; gap: 5px; padding: 14px 16px; }
.typing span {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--cs-theme);
  opacity: 0.4;
  animation: cs-typing 1.1s infinite ease-in-out;
}
.typing span:nth-child(2) { animation-delay: 0.15s; }
.typing span:nth-child(3) { animation-delay: 0.3s; }
@keyframes cs-typing {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.35; }
  40% { transform: translateY(-4px); opacity: 1; }
}
.cs-bottom-anchor { height: 1px; }

.cs-quick-row {
  display: flex; flex-wrap: wrap; gap: 8px;
  padding: 4px 22px 12px;
}
.cs-quick-chip {
  padding: 7px 13px;
  border-radius: 999px;
  border: 1px solid var(--cs-theme-strong);
  background: var(--cs-theme-soft);
  color: var(--cs-theme);
  font-size: 12.5px;
  cursor: pointer;
  transition: all 160ms ease;
}
.cs-quick-chip:hover:not(:disabled) { background: var(--cs-theme); color: #fff; }
.cs-quick-chip:disabled { opacity: 0.5; cursor: not-allowed; }

.cs-composer {
  display: flex; align-items: flex-end; gap: 10px;
  border-top: 1px solid var(--line);
  padding: 14px 18px 16px;
  background: var(--bg-surface);
}
.cs-input {
  flex: 1;
  resize: none;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 10px 13px;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-1);
  background: var(--bg-surface);
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}
.cs-input:focus { border-color: var(--cs-theme); box-shadow: 0 0 0 3px var(--cs-theme-soft); }
.cs-input::placeholder { color: var(--ink-4); }
.cs-send-btn {
  width: 42px; height: 42px; flex-shrink: 0;
  border: none; border-radius: 12px;
  background: linear-gradient(180deg, var(--cs-theme), var(--cs-theme-strong));
  color: #fff; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: filter 160ms ease, opacity 160ms ease;
}
.cs-send-btn:hover:not(:disabled) { filter: brightness(1.06); }
.cs-send-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.cs-composer.closed { justify-content: space-between; align-items: center; }
.closed-text { font-size: 13px; color: var(--ink-3); }
.cs-primary-btn {
  height: 34px; padding: 0 16px; border: none; border-radius: 10px;
  background: linear-gradient(180deg, var(--cs-theme), var(--cs-theme-strong));
  color: #fff; font-size: 13px; font-weight: 600; cursor: pointer;
}

.cs-side { width: 268px; flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; overflow-y: auto; }
.cs-side-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 15px 17px;
  box-shadow: var(--shadow-card);
}
.side-title { font-size: 12px; font-weight: 700; color: var(--ink-3); letter-spacing: 0.04em; margin-bottom: 7px; }
.side-value { font-size: 14px; font-weight: 600; color: var(--ink-1); }
.side-tip { font-size: 12px; color: var(--ink-3); line-height: 1.65; margin-top: 6px; }

.cs-footer {
  text-align: center;
  padding: 10px 0 14px;
  font-size: 11.5px;
  color: var(--ink-4);
  flex-shrink: 0;
}

.rating-body { display: flex; flex-direction: column; gap: 14px; }
.rating-hint { font-size: 13px; color: var(--ink-3); }

/* Markdown 内容 */
.md-body :deep(p) { margin: 0 0 6px; }
.md-body :deep(p:last-child) { margin-bottom: 0; }
.md-body :deep(ul), .md-body :deep(ol) { padding-left: 18px; margin: 4px 0; }
.md-body :deep(code) {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12.5px;
  background: var(--bg-hover);
  padding: 1px 5px;
  border-radius: 4px;
}
.md-body :deep(pre) {
  background: var(--bg-hover);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 6px 0;
}
.md-body :deep(pre code) { background: none; padding: 0; }
.md-body :deep(a) { color: var(--cs-theme); text-decoration: underline; }

@media (max-width: 900px) {
  .cs-main { flex-direction: column; padding: 14px 14px 8px; }
  .cs-side { width: 100%; flex-direction: row; flex-wrap: wrap; }
  .cs-side-card { flex: 1; min-width: 180px; }
  .cs-header { padding: 14px 16px; }
  .cs-title { font-size: 17px; }
  .cs-subtitle { display: none; }
  .cs-messages { padding: 16px 14px 6px; }
  .cs-bubble-wrap { max-width: 84%; }
  .cs-quick-row { padding: 4px 14px 10px; }
  .cs-composer { padding: 10px 12px 12px; }
}
</style>
