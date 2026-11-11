<template>
  <div class="crew-page">
    <!-- ────────────────────── Hero ────────────────────── -->
    <header class="crew-hero" v-if="!activeTask">
      <div class="hero-eyebrow">
        <span class="eyebrow-dot"></span>
        <span class="eyebrow-text">MULTI-AGENT RESEARCH CREW</span>
      </div>
      <h1 class="hero-title">
        <span class="title-gradient">Four Agent 协作</span>
        深度调研引擎
      </h1>
      <p class="hero-desc">
        Planner 拆解任务 → Researcher 并行检索 → Writer 撰写报告 → Critic 评审重写。
        把一个复杂问题，交付为一份结构化、可溯源、可追溯每一步推理的研究报告。
      </p>

      <form class="hero-form" @submit.prevent="handleStart">
        <textarea
          v-model="query"
          class="hero-input"
          placeholder="输入一个研究型问题，例如：对比 MindCrew 与传统 RAG 的核心差异，并给出选型建议"
          rows="3"
          :disabled="loading"
        ></textarea>
        <div class="hero-actions">
          <div class="hero-tips">
            <span class="tip-pill"><span class="dot blue"></span>Four Agent 协作</span>
            <span class="tip-pill"><span class="dot green"></span>实时事件流</span>
            <span class="tip-pill"><span class="dot violet"></span>带引用报告</span>
          </div>
          <button class="cta-start" type="submit" :disabled="!query.trim() || loading">
            <span>{{ loading ? '启动中…' : '启动调研' }}</span>
            <el-icon v-if="!loading" :size="16"><Right /></el-icon>
          </button>
        </div>
      </form>

      <!-- 历史任务 -->
      <section class="history" v-if="historyList.length">
        <div class="history-head">
          <span class="history-title">最近的调研任务</span>
        </div>
        <ul class="history-list">
          <li
            v-for="t in historyList"
            :key="t.id"
            class="history-item"
            @click="viewTask(t.id)"
          >
            <span class="hist-status" :class="t.status.toLowerCase()">{{ statusLabel(t.status) }}</span>
            <span class="hist-query">{{ t.query }}</span>
            <span class="hist-time">{{ formatTime(t.createTime) }}</span>
            <button
              class="hist-graph"
              v-if="t.status === 'COMPLETED' || t.status === 'FAILED'"
              @click.stop="openGraph(t.id)"
              title="Agent 通信图谱"
            >
              <el-icon :size="13"><Share /></el-icon>
              <span>图谱</span>
            </button>
            <button
              class="hist-replay"
              v-if="t.status === 'COMPLETED' || t.status === 'FAILED'"
              @click.stop="openReplay(t.id)"
              title="回放推理链"
            >
              <el-icon :size="13"><VideoPlay /></el-icon>
              <span>回放</span>
            </button>
          </li>
        </ul>
      </section>
    </header>

    <!-- ────────────────────── 工作区 ────────────────────── -->
    <main class="crew-stage" v-else>
      <header class="stage-head">
        <button class="back-btn" @click="resetTask">
          <el-icon :size="14"><Back /></el-icon><span>新调研</span>
        </button>
        <div class="stage-query">{{ activeTask.query }}</div>
        <div class="stage-meta">
          <span class="meta-chip" :class="statusClass">{{ statusLabel(activeTask.status) }}</span>
          <span v-if="elapsedSec" class="meta-elapsed">{{ elapsedSec }}s</span>
        </div>
      </header>

      <!-- 进度条 -->
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: (progress * 100) + '%' }"></div>
      </div>

      <!-- 4 Agent 状态卡 -->
      <section class="agent-row">
        <div
          v-for="agent in agentLanes"
          :key="agent.role"
          class="agent-card"
          :class="agentClass(agent.role)"
        >
          <div class="agent-head">
            <div class="agent-ic" :style="{ background: agent.tone, color: agent.accent }">
              <component :is="agent.icon" />
            </div>
            <div class="agent-text">
              <div class="agent-label">{{ agent.label }}</div>
              <div class="agent-sub">{{ agent.duty }}</div>
            </div>
            <span class="agent-status">{{ agentStatusText(agent.role) }}</span>
          </div>
          <div class="agent-body">
            <div v-if="agent.role === 'PLANNER' && plan.length" class="lane-content">
              <div v-for="p in plan" :key="p.index" class="lane-row">
                <span class="lane-idx">#{{ p.index }}</span>
                <span class="lane-text">{{ p.title }}</span>
              </div>
            </div>
            <div v-else-if="agent.role === 'RESEARCHER' && findings.length" class="lane-content">
              <div v-for="f in findings" :key="f.planIndex" class="lane-row">
                <span class="lane-idx done">✓</span>
                <span class="lane-text">{{ f.title }}</span>
                <span class="lane-cite">{{ f.sources?.length || 0 }} 引用</span>
              </div>
            </div>
            <div v-else-if="agent.role === 'WRITER' && reportLength" class="lane-content">
              <div class="lane-stat">
                <span class="stat-big">{{ reportLength }}</span>
                <span class="stat-unit">字符</span>
              </div>
            </div>
            <div v-else-if="agent.role === 'CRITIC' && review" class="lane-content">
              <div class="lane-stat">
                <span class="stat-big" :class="review.passed ? 'good' : 'warn'">{{ (review.score * 100).toFixed(0) }}</span>
                <span class="stat-unit">/ 100</span>
              </div>
              <div class="critic-dims">
                <div class="dim"><span>事实</span><strong>{{ (review.factuality*100).toFixed(0) }}</strong></div>
                <div class="dim"><span>完整</span><strong>{{ (review.completeness*100).toFixed(0) }}</strong></div>
                <div class="dim"><span>引用</span><strong>{{ (review.citationCoverage*100).toFixed(0) }}</strong></div>
              </div>
            </div>
            <div v-else class="lane-empty">{{ agentStatusText(agent.role) === '已完成' ? '已完成' : '等待中…' }}</div>
          </div>
        </div>
      </section>

      <!-- 主体区域：时间线 + 报告 -->
      <section class="stage-body">
        <!-- 左侧：步骤时间线 -->
        <aside class="timeline">
          <div class="timeline-head">事件流</div>
          <ol class="timeline-list">
            <li
              v-for="(ev, i) in timeline"
              :key="i"
              class="tl-item"
              :class="ev.tone"
            >
              <span class="tl-dot"></span>
              <div class="tl-body">
                <div class="tl-row">
                  <span class="tl-role" :class="(ev.role || '').toLowerCase()">{{ ev.role || 'SYS' }}</span>
                  <span class="tl-title">{{ ev.title }}</span>
                </div>
                <div v-if="ev.detail" class="tl-detail">{{ ev.detail }}</div>
              </div>
            </li>
          </ol>
        </aside>

        <!-- 右侧：报告 -->
        <article class="report">
          <header class="report-head">
            <div class="report-title">研究报告</div>
            <div class="report-actions" v-if="report">
              <button class="rb-btn" @click="copyReport"><el-icon :size="14"><CopyDocument /></el-icon>复制</button>
              <button class="rb-btn" @click="downloadReport"><el-icon :size="14"><Download /></el-icon>下载</button>
              <button class="rb-btn" :disabled="exportingPptx" @click="exportDeliverable('pptx')">
                <el-icon :size="14"><Document /></el-icon>{{ exportingPptx ? '导出中…' : '导出 PPT' }}
              </button>
              <button class="rb-btn" :disabled="exportingDocx" @click="exportDeliverable('docx')">
                <el-icon :size="14"><Document /></el-icon>{{ exportingDocx ? '导出中…' : '导出 Word' }}
              </button>
            </div>
          </header>
          <div class="report-body md-body" v-if="report" v-html="renderedReport"></div>
          <div class="report-empty" v-else>
            <div class="empty-ic">
              <el-icon :size="34"><Document /></el-icon>
            </div>
            <div class="empty-msg">{{ writerStarted ? 'Writer 正在撰写中…' : '等待前序 Agent 完成' }}</div>
          </div>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Right, Back, Document, CopyDocument, Download, DataAnalysis, Search, EditPen, Aim, VideoPlay, Share } from '@element-plus/icons-vue'
import { marked } from 'marked'
import { crewApi, type AgentTask, type PlanItem, type Finding, type ReviewResult } from '@/api/crew'
import { officeApi } from '@/api/office'
import { downloadBlob } from '@/utils/download'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const openReplay = (id: number) => router.push(`/crew/replay/${id}`)
const openGraph  = (id: number) => router.push(`/crew/graph/${id}`)

const userStore = useUserStore()

const query = ref('')
const loading = ref(false)
const activeTask = ref<AgentTask | null>(null)

const plan       = ref<PlanItem[]>([])
const findings   = ref<Finding[]>([])
const report     = ref('')
const review     = ref<ReviewResult | null>(null)
const writerStarted = ref(false)
const progress   = ref(0)

const agentStatus = ref<Record<string, 'idle' | 'working' | 'done' | 'failed'>>({
  PLANNER: 'idle', RESEARCHER: 'idle', WRITER: 'idle', CRITIC: 'idle'
})

interface TLEvent { role?: string; title: string; detail?: string; tone?: string }
const timeline = ref<TLEvent[]>([])

const historyList = ref<AgentTask[]>([])
const elapsedSec = ref(0)
const exportingPptx = ref(false)
const exportingDocx = ref(false)
let elapsedTimer: number | undefined
let eventSource: EventSource | null = null

const agentLanes = [
  { role: 'PLANNER',    label: '任务规划师', duty: '分解为子主题',          icon: Aim,          tone: '#EEF1FF',  accent: '#3D5AFE' },
  { role: 'RESEARCHER', label: '调研员',     duty: '并行多路检索',          icon: Search,       tone: '#E0F2FE',  accent: '#0EA5E9' },
  { role: 'WRITER',     label: '撰写员',     duty: '合成结构化报告',        icon: EditPen,      tone: '#F3F1FF',  accent: '#7C3AED' },
  { role: 'CRITIC',     label: '评审员',     duty: '评分+反馈+决定重写',    icon: DataAnalysis, tone: '#DCFCE7',  accent: '#10B981' },
]

const reportLength = computed(() => report.value ? report.value.length : 0)

const renderedReport = computed(() => report.value ? (marked.parse(report.value) as string) : '')

const statusClass = computed(() => 'status-' + (activeTask.value?.status || '').toLowerCase())

const statusLabel = (s: string) => {
  const map: Record<string, string> = {
    PENDING: '待启动', PLANNING: '规划中', RESEARCHING: '调研中',
    WRITING: '撰写中', REVIEWING: '评审中', REVISING: '重写中',
    COMPLETED: '已完成', FAILED: '失败'
  }
  return map[s] || s
}

const agentStatusText = (role: string) => {
  const s = agentStatus.value[role]
  return s === 'working' ? '进行中' : s === 'done' ? '已完成' : s === 'failed' ? '失败' : '等待中'
}

const agentClass = (role: string) => {
  return 'state-' + agentStatus.value[role]
}

const formatTime = (s: string) => {
  if (!s) return ''
  const d = new Date(s.replace(' ', 'T'))
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// ─── 启动 ─────────────────────────────────
const handleStart = async () => {
  if (!query.value.trim() || loading.value) return
  loading.value = true
  try {
    const res: any = await crewApi.createTask(query.value.trim())
    const taskId = res.data?.taskId ?? res.taskId
    if (!taskId) throw new Error('taskId missing in response')

    activeTask.value = {
      id: taskId, userId: 0, query: query.value, status: 'PENDING',
      revisionCount: 0, totalSteps: 0, totalTokens: 0, elapsedMs: 0,
      createTime: new Date().toISOString()
    }

    startElapsed()
    subscribeStream(taskId)
  } catch (e: any) {
    ElMessage.error('启动失败：' + (e?.message || e))
    loading.value = false
  }
}

// ─── 订阅 SSE ─────────────────────────────
const subscribeStream = (taskId: number) => {
  if (eventSource) eventSource.close()
  const source = crewApi.streamTask(taskId, userStore.token)
  eventSource = source

  const evs = [
    'task.start','task.done','task.failed',
    'agent.start','agent.done','agent.failed',
    'planner.plan',
    'researcher.start','researcher.finding',
    'writer.token','writer.done',
    'critic.review','revision.start'
  ]

  evs.forEach(name => source.addEventListener(name, (e: MessageEvent) => handleEvent(name, e.data)))

  source.onerror = () => {
    log('error', '连接中断')
    source.close()
    eventSource = null
  }
}

const handleEvent = (name: string, raw: any) => {
  let payload: any = {}
  try { payload = JSON.parse(raw) } catch { /* ignore */ }
  const data = payload.data || {}

  if (payload.progress != null) progress.value = payload.progress

  switch (name) {
    case 'task.start':
      log('PLANNER', '任务启动', payload.data?.query); break

    case 'planner.plan':
      plan.value = data.plan || []
      agentStatus.value.PLANNER = 'done'
      log('PLANNER', '任务分解完成', `${plan.value.length} 个子主题`)
      break

    case 'agent.start':
      if (payload.role) agentStatus.value[payload.role] = 'working'
      break

    case 'agent.done':
      if (payload.role) agentStatus.value[payload.role] = 'done'
      break

    case 'researcher.start':
      log('RESEARCHER', `开始调研：${data.title}`)
      break

    case 'researcher.finding':
      const f: Finding = data.finding
      if (f) {
        findings.value = [...findings.value, f].sort((a, b) => a.planIndex - b.planIndex)
        log('RESEARCHER', `完成：${f.title}`, `${f.sources?.length || 0} 处引用`)
      }
      break

    case 'writer.token':
      writerStarted.value = true
      if (data.delta) report.value += data.delta
      break

    case 'writer.done':
      report.value = data.report || report.value
      log('WRITER', '报告撰写完成', `${(data.report || '').length} 字符`)
      break

    case 'critic.review':
      review.value = data.review
      log('CRITIC', `评审完成（${review.value?.passed ? '通过' : '未通过'}）`,
          `综合分 ${((review.value?.score || 0) * 100).toFixed(0)}/100`)
      break

    case 'revision.start':
      report.value = ''
      log('CRITIC', '触发重写', data.reason)
      break

    case 'task.done':
      activeTask.value!.status = 'COMPLETED'
      stopElapsed()
      loading.value = false
      log('SYS', '任务完成', `用时 ${(data.elapsedMs / 1000).toFixed(1)}s`)
      ElMessage.success('调研完成')
      eventSource?.close()
      loadHistory()
      break

    case 'task.failed':
      activeTask.value!.status = 'FAILED'
      stopElapsed()
      loading.value = false
      log('SYS', '任务失败', data.error)
      ElMessage.error('任务失败：' + data.error)
      eventSource?.close()
      break
  }
}

const log = (role: string, title: string, detail?: string) => {
  timeline.value.push({ role, title, detail })
}

// ─── 计时 ─────────────────────────────────
const startElapsed = () => {
  elapsedSec.value = 0
  elapsedTimer = window.setInterval(() => { elapsedSec.value++ }, 1000)
}
const stopElapsed = () => {
  if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = undefined }
}

// ─── 重置 / 历史 ──────────────────────────
const resetTask = () => {
  activeTask.value = null
  plan.value = []
  findings.value = []
  report.value = ''
  review.value = null
  writerStarted.value = false
  progress.value = 0
  timeline.value = []
  agentStatus.value = { PLANNER: 'idle', RESEARCHER: 'idle', WRITER: 'idle', CRITIC: 'idle' }
  eventSource?.close()
  eventSource = null
  stopElapsed()
  loading.value = false
}

const viewTask = async (id: number) => {
  try {
    const res: any = await crewApi.getTask(id)
    const data = res.data || res
    activeTask.value = data.task
    if (data.task.planJson) {
      try { plan.value = JSON.parse(data.task.planJson) } catch { /* ignore */ }
    }
    report.value = data.task.finalReport || ''
    progress.value = data.task.status === 'COMPLETED' ? 1 : 0
    agentStatus.value = {
      PLANNER: 'done', RESEARCHER: 'done', WRITER: 'done', CRITIC: 'done'
    }
    // 时间线从 steps 重建
    data.steps?.forEach((s: any) => {
      log(s.agentRole, s.stepName, s.status === 'DONE' ? `${s.elapsedMs}ms` : s.errorMsg)
    })
  } catch (e: any) {
    ElMessage.error('加载失败')
  }
}

const loadHistory = async () => {
  try {
    const res: any = await crewApi.listTasks({ current: 1, size: 8 })
    historyList.value = (res.data?.records || res.records || []).slice(0, 8)
  } catch { /* ignore */ }
}

// ─── 报告操作 ─────────────────────────────
const copyReport = async () => {
  try {
    await navigator.clipboard.writeText(report.value)
    ElMessage.success('已复制')
  } catch { ElMessage.error('复制失败') }
}

const downloadReport = () => {
  const blob = new Blob([report.value], { type: 'text/markdown; charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `crew-report-${activeTask.value?.id || Date.now()}.md`
  a.click()
  URL.revokeObjectURL(url)
}

/** 导出 PPT / Word 交付物（后端 POI 生成，文件流下载） */
const exportDeliverable = async (format: 'pptx' | 'docx') => {
  const taskId = activeTask.value?.id
  if (!taskId) {
    ElMessage.warning('请先打开一个调研任务')
    return
  }
  const loadingFlag = format === 'pptx' ? exportingPptx : exportingDocx
  loadingFlag.value = true
  try {
    const file = await officeApi.downloadCrewDeliverable(taskId, format)
    downloadBlob(file.blob, file.filename || `crew-report-${taskId}.${format}`)
    ElMessage.success(format === 'pptx' ? 'PPT 已导出' : 'Word 已导出')
  } catch (e: any) {
    ElMessage.error('导出失败：' + (e?.message || '未知错误'))
  } finally {
    loadingFlag.value = false
  }
}

onMounted(loadHistory)
onBeforeUnmount(() => {
  eventSource?.close()
  stopElapsed()
})
</script>

<style scoped>
.crew-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 32px 36px 64px;
  background: var(--bg-page);
  scrollbar-gutter: stable;
}

/* ───── Hero ───── */
.crew-hero {
  max-width: 880px;
  margin: 40px auto 0;
}
.hero-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px 5px 8px;
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand-ink);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  margin-bottom: 22px;
}
.eyebrow-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--brand);
  box-shadow: 0 0 0 4px rgba(61, 90, 254, 0.18);
  animation: pulse-soft 1.8s ease-in-out infinite;
}

.hero-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 42px;
  font-weight: 800;
  line-height: 1.18;
  color: var(--ink-1);
  letter-spacing: -0.025em;
  margin-bottom: 16px;
}
.title-gradient {
  background: linear-gradient(120deg, var(--brand) 0%, #7C3AED 40%, #EC4899 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  display: block;
}

.hero-desc {
  font-size: 15px;
  line-height: 1.75;
  color: var(--ink-2);
  max-width: 680px;
  margin-bottom: 28px;
}

.hero-form {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: 18px 20px 16px;
  transition: var(--transition);
}
.hero-form:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-lg);
}
.hero-input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-family: inherit;
  font-size: 15px;
  line-height: 1.6;
  color: var(--ink-1);
  resize: none;
}
.hero-input::placeholder { color: var(--ink-4); }

.hero-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}
.hero-tips { display: flex; gap: 10px; flex-wrap: wrap; }
.tip-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--bg-subtle);
  border-radius: 999px;
  font-size: 12px;
  color: var(--ink-2);
}
.dot { width: 6px; height: 6px; border-radius: 50%; }
.dot.blue   { background: #3D5AFE; }
.dot.green  { background: #10B981; }
.dot.violet { background: #7C3AED; }

.cta-start {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 22px;
  border-radius: 10px;
  border: none;
  background: linear-gradient(180deg, var(--brand-hover), var(--brand));
  color: #fff;
  font-family: 'Manrope', sans-serif;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
  box-shadow: var(--shadow-brand);
  cursor: pointer;
  transition: filter 180ms, box-shadow 180ms;
}
.cta-start:hover:not(:disabled) { filter: brightness(1.06); }
.cta-start:disabled { opacity: 0.5; cursor: not-allowed; }

/* ───── 历史 ───── */
.history { margin-top: 36px; }
.history-head { margin-bottom: 10px; }
.history-title { font-size: 12px; font-weight: 600; color: var(--ink-3); letter-spacing: 0.08em; text-transform: uppercase; }
.history-list { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.history-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  cursor: pointer;
  transition: var(--transition);
}
.history-item:hover { border-color: var(--brand-soft-2); background: var(--brand-soft); }
.hist-status {
  font-size: 10px; font-weight: 700; letter-spacing: 0.06em;
  padding: 3px 7px; border-radius: 4px;
  background: var(--bg-subtle); color: var(--ink-3);
}
.hist-status.completed { background: var(--success-soft); color: var(--success-ink); }
.hist-status.failed    { background: var(--danger-soft);  color: var(--danger-ink); }
.hist-query { flex: 1; font-size: 13.5px; color: var(--ink-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-time { font-size: 11px; color: var(--ink-4); font-family: 'JetBrains Mono', monospace; }
.hist-replay,
.hist-graph {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 11px 5px 9px;
  border-radius: 8px;
  background: transparent;
  border: 1px solid var(--line);
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.01em;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 180ms var(--ease);
}
.hist-replay :deep(.el-icon),
.hist-graph  :deep(.el-icon) { color: currentColor; }

/* 父行 hover 时，按钮微微提亮（保持低调） */
.history-item:hover .hist-graph,
.history-item:hover .hist-replay {
  background: rgba(255, 255, 255, 0.7);
  border-color: var(--brand-soft-2);
  color: var(--ink-2);
}

/* 按钮本身 hover 时才显色（图谱深色，回放品牌色） */
.hist-graph:hover {
  background: var(--ink-1);
  border-color: var(--ink-1);
  color: #fff;
  box-shadow: 0 4px 12px rgba(11, 20, 38, 0.18);
}
.hist-replay:hover {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
  box-shadow: 0 4px 12px rgba(61, 90, 254, 0.28);
}
.hist-graph:active,
.hist-replay:active { transform: scale(0.97); }

/* ───── 工作区 ───── */
.crew-stage {
  max-width: 1280px;
  margin: 0 auto;
}
.stage-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--ink-2);
  font-size: 12.5px;
  cursor: pointer;
  transition: var(--transition);
}
.back-btn:hover { border-color: var(--brand); color: var(--brand); }
.stage-query {
  flex: 1;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stage-meta { display: flex; align-items: center; gap: 10px; }
.meta-chip {
  display: inline-block;
  padding: 4px 10px;
  background: var(--brand-soft);
  color: var(--brand-ink);
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 600;
}
.meta-chip.status-completed { background: var(--success-soft); color: var(--success-ink); }
.meta-chip.status-failed    { background: var(--danger-soft);  color: var(--danger-ink); }
.meta-elapsed {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12.5px;
  color: var(--ink-3);
  font-weight: 600;
}

/* 进度条 */
.progress-bar {
  height: 3px;
  background: var(--line-soft);
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 22px;
}
.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--brand-hover), #7C3AED);
  border-radius: 2px;
  transition: width 400ms var(--ease);
}

/* Agent 卡片 */
.agent-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 22px;
}
.agent-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px;
  position: relative;
  transition: var(--transition);
}
.agent-card.state-working {
  border-color: var(--brand-soft-2);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-sm);
}
.agent-card.state-done { border-color: rgba(16, 185, 129, 0.4); }

.agent-card::before {
  content: '';
  position: absolute;
  top: -1px; left: -1px; right: -1px; height: 2px;
  border-radius: var(--radius) var(--radius) 0 0;
  background: var(--line);
}
.agent-card.state-working::before {
  background: linear-gradient(90deg, var(--brand), #7C3AED);
  background-size: 200% 100%;
  animation: shimmer-brand 1.5s linear infinite;
}
.agent-card.state-done::before { background: var(--success); }

.agent-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.agent-ic {
  width: 36px; height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.agent-text { flex: 1; min-width: 0; }
.agent-label { font-family: 'Manrope', sans-serif; font-size: 14px; font-weight: 700; color: var(--ink-1); }
.agent-sub { font-size: 11px; color: var(--ink-3); margin-top: 1px; }
.agent-status {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.05em;
  padding: 3px 8px;
  border-radius: 4px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  flex-shrink: 0;
}
.state-working .agent-status { background: var(--brand-soft); color: var(--brand-ink); }
.state-done    .agent-status { background: var(--success-soft); color: var(--success-ink); }
.state-failed  .agent-status { background: var(--danger-soft); color: var(--danger-ink); }

.agent-body { min-height: 80px; }
.lane-content { display: flex; flex-direction: column; gap: 6px; }
.lane-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink-2);
}
.lane-idx {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-4);
  width: 22px;
  flex-shrink: 0;
}
.lane-idx.done { color: var(--success); }
.lane-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lane-cite { font-size: 11px; color: var(--ink-4); font-family: 'JetBrains Mono', monospace; }
.lane-empty { color: var(--ink-4); font-size: 12.5px; padding: 14px 0; text-align: center; }

.lane-stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 8px;
}
.stat-big {
  font-family: 'Manrope', sans-serif;
  font-size: 28px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.02em;
  line-height: 1;
}
.stat-big.good { color: var(--success-ink); }
.stat-big.warn { color: var(--warning-ink); }
.stat-unit { font-size: 12px; color: var(--ink-3); }

.critic-dims {
  display: flex;
  gap: 10px;
  margin-top: 6px;
  font-size: 11px;
}
.critic-dims .dim {
  flex: 1;
  background: var(--bg-subtle);
  padding: 6px 8px;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.critic-dims .dim span { color: var(--ink-3); }
.critic-dims .dim strong { font-family: 'JetBrains Mono', monospace; color: var(--ink-1); font-size: 13px; }

/* ───── 主体 ───── */
.stage-body {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 18px;
  min-height: 480px;
}

.timeline {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px;
  height: fit-content;
  max-height: 600px;
  overflow-y: auto;
}
.timeline-head {
  font-family: 'Manrope', sans-serif;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--ink-3);
  text-transform: uppercase;
  margin-bottom: 14px;
}
.timeline-list { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 12px; position: relative; }
.timeline-list::before {
  content: '';
  position: absolute;
  left: 5px; top: 6px; bottom: 6px;
  width: 1px;
  background: var(--line);
}
.tl-item { display: flex; gap: 12px; position: relative; }
.tl-dot {
  width: 11px; height: 11px;
  border-radius: 50%;
  background: var(--bg-surface);
  border: 2px solid var(--line-strong);
  flex-shrink: 0;
  margin-top: 3px;
  z-index: 1;
}
.tl-item.error .tl-dot { border-color: var(--danger); background: var(--danger-soft); }
.tl-body { flex: 1; min-width: 0; }
.tl-row { display: flex; align-items: center; gap: 8px; }
.tl-role {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  letter-spacing: 0.04em;
}
.tl-role.planner    { background: var(--brand-soft); color: var(--brand-ink); }
.tl-role.researcher { background: var(--info-soft);  color: var(--info-ink); }
.tl-role.writer     { background: #F3F1FF; color: #6B21A8; }
.tl-role.critic     { background: var(--success-soft); color: var(--success-ink); }
.tl-title { font-size: 12.5px; color: var(--ink-1); font-weight: 500; }
.tl-detail { font-size: 11.5px; color: var(--ink-3); margin-top: 3px; line-height: 1.5; }

.report {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.report-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line-soft);
}
.report-title {
  font-family: 'Manrope', sans-serif;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.01em;
}
.report-actions { display: flex; gap: 8px; }
.rb-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
  transition: var(--transition);
}
.rb-btn:hover { background: var(--brand-soft); color: var(--brand-ink); border-color: var(--brand-soft-2); }

.report-body {
  padding: 24px 28px;
  overflow-y: auto;
  flex: 1;
}
.report-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: var(--ink-4);
}
.empty-ic {
  width: 60px; height: 60px;
  border-radius: 16px;
  background: var(--bg-subtle);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
  color: var(--ink-4);
}
.empty-msg { font-size: 13px; }

/* 响应式 */
@media (max-width: 1100px) {
  .agent-row { grid-template-columns: repeat(2, 1fr); }
  .stage-body { grid-template-columns: 1fr; }
}
</style>
