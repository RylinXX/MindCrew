<template>
  <div class="replay-page">
    <!-- ─────────────── 顶部：任务概览 ─────────────── -->
    <header class="hero">
      <button class="back" @click="goBack">
        <el-icon :size="14"><Back /></el-icon><span>返回任务列表</span>
      </button>

      <div class="hero-main" v-if="task">
        <div class="hero-tags">
          <span class="kbd-tag">TRACE REPLAY</span>
          <span class="status-tag" :class="task.status.toLowerCase()">{{ statusLabel(task.status) }}</span>
          <span class="time-tag">{{ formatTime(task.createTime) }}</span>
        </div>
        <h1 class="hero-query">{{ task.query }}</h1>
        <div class="hero-stats">
          <div class="stat">
            <span class="stat-val">{{ task.totalSteps || steps.length }}</span>
            <span class="stat-lab">步骤</span>
          </div>
          <span class="stat-sep"></span>
          <div class="stat">
            <span class="stat-val">{{ formatDuration(task.elapsedMs) }}</span>
            <span class="stat-lab">总耗时</span>
          </div>
          <span class="stat-sep"></span>
          <div class="stat">
            <span class="stat-val">{{ task.revisionCount || 0 }}</span>
            <span class="stat-lab">重写次数</span>
          </div>
          <span class="stat-sep" v-if="task.reviewScore"></span>
          <div class="stat" v-if="task.reviewScore">
            <span class="stat-val score">{{ (task.reviewScore * 100).toFixed(0) }}</span>
            <span class="stat-lab">综合评分</span>
          </div>
        </div>
      </div>
    </header>

    <!-- ─────────────── 4 Agent 泳道概览 ─────────────── -->
    <section class="lanes" v-if="steps.length">
      <div
        v-for="lane in lanes"
        :key="lane.role"
        class="lane"
        :class="{ active: currentRole === lane.role }"
      >
        <div class="lane-head">
          <div class="lane-badge" :style="{ background: lane.tone, color: lane.fg, borderColor: lane.line }">
            <component :is="lane.icon" />
          </div>
          <div class="lane-meta">
            <div class="lane-name">{{ lane.label }}</div>
            <div class="lane-duty">{{ lane.duty }}</div>
          </div>
          <div class="lane-stat-mini">
            <span class="lane-count">{{ countByRole(lane.role) }}</span>
            <span class="lane-count-lbl">步骤</span>
          </div>
        </div>
        <div class="lane-dots">
          <span
            v-for="s in stepsByRole(lane.role)"
            :key="s.id"
            class="dot"
            :class="{
              done: s.status === 'DONE' && s.stepIndex <= playIndex,
              fail: s.status === 'FAILED',
              now:  s.stepIndex === playIndex,
              pending: s.stepIndex > playIndex
            }"
            :title="s.stepName"
            @click="seekTo(s.stepIndex)"
          ></span>
        </div>
      </div>
    </section>

    <!-- ─────────────── 时间线 ─────────────── -->
    <section class="content" v-if="steps.length">
      <div class="timeline" ref="timelineRef">
        <article
          v-for="s in steps"
          :key="s.id"
          :ref="(el) => stepRefs.set(s.stepIndex, el as any)"
          class="step"
          :class="{
            past: s.stepIndex < playIndex,
            now:  s.stepIndex === playIndex,
            future: s.stepIndex > playIndex,
            failed: s.status === 'FAILED'
          }"
          :data-role="s.agentRole"
          @click="seekTo(s.stepIndex)"
        >
          <div class="step-rail">
            <span class="rail-node" :style="nodeStyle(s)"></span>
            <span class="rail-line" v-if="s.stepIndex < steps.length"></span>
          </div>

          <div class="step-card">
            <header class="step-head">
              <span class="step-num">#{{ s.stepIndex }}</span>
              <span class="step-role" :style="{ background: roleTone(s.agentRole), color: roleFg(s.agentRole) }">
                {{ roleLabel(s.agentRole) }}
              </span>
              <span class="step-name">{{ s.stepName }}</span>
              <span class="step-status" :class="s.status.toLowerCase()">{{ statusText(s.status) }}</span>
              <span class="step-time">{{ formatStepMs(s.elapsedMs) }}</span>
            </header>

            <div class="step-body">
              <div v-if="s.subtask" class="step-block">
                <div class="block-lbl">子任务</div>
                <div class="block-val">{{ s.subtask }}</div>
              </div>
              <div v-if="s.input && s.stepIndex <= playIndex" class="step-block">
                <div class="block-lbl">输入</div>
                <div class="block-val mono">{{ truncate(s.input, 240) }}</div>
              </div>
              <div v-if="s.output && s.stepIndex <= playIndex" class="step-block">
                <div class="block-lbl">输出</div>
                <pre class="block-val output mono" v-html="formatOutput(s.output, s.agentRole)"></pre>
              </div>
              <div v-if="s.errorMsg" class="step-block error">
                <div class="block-lbl">错误</div>
                <div class="block-val">{{ s.errorMsg }}</div>
              </div>

              <!-- Time-Travel · 编辑并重跑 -->
              <div class="step-actions" v-if="canFork(s)">
                <button class="fork-btn" @click.stop="openForkDialog(s)">
                  <el-icon :size="13"><EditPen /></el-icon>
                  <span>编辑并重跑</span>
                  <span class="fork-hint">从此步开始 Fork</span>
                </button>
              </div>
            </div>
          </div>
        </article>

        <!-- 终点标志 -->
        <div class="finish" v-if="playIndex >= steps.length">
          <div class="finish-ring"></div>
          <div class="finish-text">推理链回放完成</div>
        </div>
      </div>
    </section>

    <!-- ─────────────── 空 / 加载状态 ─────────────── -->
    <section class="empty" v-if="!loading && !steps.length">
      <div class="empty-ring"></div>
      <p>暂无可回放的步骤</p>
    </section>

    <!-- ─────────────── Time-Travel · Fork 弹窗 ─────────────── -->
    <Teleport to="body">
      <div v-if="forkDialog.open" class="fork-modal" @click.self="closeForkDialog">
        <div class="fork-card">
          <header class="fork-head">
            <div class="fork-head-l">
              <span class="fork-kbd">TIME-TRAVEL</span>
              <h2 class="fork-title">编辑并重跑 · #{{ forkDialog.step?.stepIndex }} {{ roleLabelText(forkDialog.step?.agentRole) }}</h2>
              <p class="fork-sub">修改下方输出 → 后续 Agent 将基于新输出重新执行</p>
            </div>
            <button class="fork-close" @click="closeForkDialog">
              <el-icon :size="16"><Close /></el-icon>
            </button>
          </header>

          <div class="fork-hint-box" v-if="forkHintText">
            <el-icon :size="14"><InfoFilled /></el-icon>
            <span>{{ forkHintText }}</span>
          </div>

          <label class="fork-lbl">编辑后的输出</label>
          <textarea
            v-model="forkDialog.editedOutput"
            class="fork-textarea mono"
            rows="14"
            spellcheck="false"
            placeholder="编辑后的输出（JSON 或文本）"
          ></textarea>

          <label class="fork-lbl">编辑说明（可选，方便日后回溯）</label>
          <input
            v-model="forkDialog.editSummary"
            type="text"
            class="fork-input"
            placeholder="例如：删除离题子任务，强化数据维度"
            maxlength="100"
          />

          <footer class="fork-foot">
            <button class="fork-cancel" @click="closeForkDialog" :disabled="forkDialog.submitting">取消</button>
            <button class="fork-submit" @click="submitFork" :disabled="forkDialog.submitting || !forkDialog.editedOutput.trim()">
              <el-icon v-if="forkDialog.submitting" :size="14" class="spin"><Loading /></el-icon>
              <span>{{ forkDialog.submitting ? '正在创建分支…' : '提交并启动 Fork' }}</span>
            </button>
          </footer>
        </div>
      </div>
    </Teleport>

    <!-- ─────────────── Fork 执行进度蒙层 ─────────────── -->
    <Teleport to="body">
      <div v-if="forkRun.open" class="fork-running" @click.self="dismissForkRun">
        <div class="fr-card">
          <div class="fr-spinner"></div>
          <h3 class="fr-title">Fork 任务执行中</h3>
          <p class="fr-status">{{ forkRun.statusText }}</p>
          <div class="fr-progress">
            <div class="fr-progress-fill" :style="{ width: (forkRun.progress * 100) + '%' }"></div>
          </div>
          <div class="fr-lanes">
            <span v-for="r in lanes" :key="r.role" class="fr-lane" :class="{ active: forkRun.currentRole === r.role }">
              <span class="fr-dot" :style="{ background: r.fg }"></span>
              <span>{{ r.label.split(' · ')[0] }}</span>
            </span>
          </div>
          <button class="fr-cancel" @click="dismissForkRun">在后台继续</button>
        </div>
      </div>
    </Teleport>

    <!-- ─────────────── 底部播放控制 ─────────────── -->
    <footer class="controls" v-if="steps.length">
      <button class="ctrl-btn" @click="seekTo(0)" :disabled="playIndex === 0" title="回到起点">
        <el-icon :size="16"><DArrowLeft /></el-icon>
      </button>
      <button class="ctrl-btn" @click="stepBack" :disabled="playIndex === 0" title="上一步">
        <el-icon :size="16"><ArrowLeft /></el-icon>
      </button>

      <button class="play-btn" @click="togglePlay">
        <el-icon :size="20" v-if="!isPlaying"><VideoPlay /></el-icon>
        <el-icon :size="20" v-else><VideoPause /></el-icon>
      </button>

      <button class="ctrl-btn" @click="stepForward" :disabled="playIndex >= steps.length" title="下一步">
        <el-icon :size="16"><ArrowRight /></el-icon>
      </button>
      <button class="ctrl-btn" @click="seekTo(steps.length)" :disabled="playIndex >= steps.length" title="跳到末尾">
        <el-icon :size="16"><DArrowRight /></el-icon>
      </button>

      <div class="scrubber">
        <div class="scrubber-track" @click="onScrub($event)">
          <div class="scrubber-fill" :style="{ width: scrubberPct + '%' }"></div>
          <div class="scrubber-thumb" :style="{ left: scrubberPct + '%' }"></div>
        </div>
        <div class="scrubber-label">{{ playIndex }} / {{ steps.length }}</div>
      </div>

      <div class="speed-group">
        <button
          v-for="sp in speeds"
          :key="sp"
          class="speed-btn"
          :class="{ active: speed === sp }"
          @click="setSpeed(sp)"
        >{{ sp }}×</button>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Back, VideoPlay, VideoPause, ArrowLeft, ArrowRight, DArrowLeft, DArrowRight,
  Aim, Search, EditPen, Stamp, Close, InfoFilled, Loading,
} from '@element-plus/icons-vue'
import { crewApi, type AgentTask, type AgentStep } from '@/api/crew'
import { useUserStore } from '@/stores/user'

const route  = useRoute()
const router = useRouter()
const taskId = Number(route.params.taskId)

const loading   = ref(true)
const task      = ref<AgentTask | null>(null)
const steps     = ref<AgentStep[]>([])
const playIndex = ref(0)
const isPlaying = ref(false)
const speed     = ref(1)
const speeds    = [0.5, 1, 2, 4]

const timelineRef = ref<HTMLElement | null>(null)
const stepRefs    = new Map<number, HTMLElement>()
let playTimer: number | null = null

// ─── 4 Agent 泳道配置 ───
const lanes = [
  { role: 'PLANNER',    label: 'Planner',    duty: '任务分解',  icon: Aim,    tone: '#EEF1FF', fg: '#3D5AFE', line: '#DBE2FF' },
  { role: 'RESEARCHER', label: 'Researcher', duty: '并行调研',  icon: Search, tone: '#E0F2FE', fg: '#0369A1', line: '#BAE6FD' },
  { role: 'WRITER',     label: 'Writer',     duty: '报告撰写',  icon: EditPen,tone: '#DCFCE7', fg: '#047857', line: '#BBF7D0' },
  { role: 'CRITIC',     label: 'Critic',     duty: '质量评审',  icon: Stamp,  tone: '#FEF3C7', fg: '#B45309', line: '#FDE68A' },
]

// ─── 计算属性 ───
const currentRole = computed(() => {
  if (playIndex.value === 0 || playIndex.value > steps.value.length) return null
  const s = steps.value[playIndex.value - 1]
  return s?.agentRole
})
const scrubberPct = computed(() => {
  if (steps.value.length === 0) return 0
  return Math.min(100, (playIndex.value / steps.value.length) * 100)
})

// ─── 数据 ───
async function load() {
  loading.value = true
  try {
    const res: any = await crewApi.getTask(taskId)
    const data = res?.data ?? res
    task.value  = data?.task ?? null
    const rawSteps: AgentStep[] = data?.steps ?? []
    // 后端 enum.getCode() 返回大写，此处兼容旧数据的驼峰格式
    steps.value = [...rawSteps]
      .map(s => ({ ...s, agentRole: (s.agentRole || '').toUpperCase() }))
      .sort((a, b) => a.stepIndex - b.stepIndex)
    playIndex.value = 0
  } catch (e) {
    ElMessage.error('加载任务失败')
  } finally {
    loading.value = false
  }
}

// ─── 播放控制 ───
function togglePlay() {
  if (playIndex.value >= steps.value.length) playIndex.value = 0
  isPlaying.value ? pause() : play()
}
function play() {
  isPlaying.value = true
  scheduleNext()
}
function pause() {
  isPlaying.value = false
  if (playTimer !== null) { clearTimeout(playTimer); playTimer = null }
}
function scheduleNext() {
  if (!isPlaying.value) return
  if (playIndex.value >= steps.value.length) { pause(); return }

  // 每一步的"展示时长"基于实际耗时但有上下限，速度倍率影响
  const s = steps.value[playIndex.value]
  const baseMs = s && s.elapsedMs ? Math.min(Math.max(s.elapsedMs, 600), 3500) : 1200
  const delay = Math.round(baseMs / speed.value)

  playTimer = window.setTimeout(() => {
    playIndex.value++
    scrollToCurrent()
    scheduleNext()
  }, delay)
}
function stepForward() {
  if (playIndex.value < steps.value.length) { playIndex.value++; scrollToCurrent() }
}
function stepBack() {
  if (playIndex.value > 0) { playIndex.value--; scrollToCurrent() }
}
function seekTo(i: number) {
  pause()
  playIndex.value = Math.max(0, Math.min(i, steps.value.length))
  scrollToCurrent()
}
function setSpeed(sp: number) {
  speed.value = sp
  if (isPlaying.value) { pause(); play() }
}
function onScrub(e: MouseEvent) {
  const el = e.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  const pct = (e.clientX - rect.left) / rect.width
  seekTo(Math.round(pct * steps.value.length))
}

async function scrollToCurrent() {
  await nextTick()
  const idx = Math.min(playIndex.value, steps.value.length)
  const el = stepRefs.get(idx) || stepRefs.get(idx - 1)
  if (el && timelineRef.value) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

// ─── UI 辅助 ───
function countByRole(role: string)     { return steps.value.filter(s => s.agentRole === role).length }
function stepsByRole(role: string)     { return steps.value.filter(s => s.agentRole === role) }
function roleLabel(role: string)       { return lanes.find(l => l.role === role)?.label || role }
function roleTone(role: string)        { return lanes.find(l => l.role === role)?.tone || '#F1F4FA' }
function roleFg(role: string)          { return lanes.find(l => l.role === role)?.fg   || '#4B5670' }
function nodeStyle(s: AgentStep) {
  const lane = lanes.find(l => l.role === s.agentRole)
  if (!lane) return {}
  if (s.stepIndex < playIndex.value)  return { background: lane.fg,  borderColor: lane.fg }
  if (s.stepIndex === playIndex.value) return { background: '#fff', borderColor: lane.fg, boxShadow: `0 0 0 4px ${lane.tone}` }
  return { background: '#fff', borderColor: '#D8DEEA' }
}
function statusText(s: string)         { return s === 'DONE' ? '已完成' : s === 'FAILED' ? '失败' : s === 'RUNNING' ? '进行中' : '已跳过' }
function statusLabel(s: string) {
  const m: Record<string, string> = {
    PENDING:'待启动', PLANNING:'规划中', RESEARCHING:'调研中', WRITING:'撰写中',
    REVIEWING:'评审中', REVISING:'重写中', COMPLETED:'已完成', FAILED:'失败'
  }
  return m[s] || s
}
function formatTime(t?: string) {
  if (!t) return ''
  const d = new Date(t.replace(' ', 'T'))
  return d.toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit' })
}
function formatDuration(ms?: number) {
  if (!ms) return '—'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60_000) return (ms / 1000).toFixed(1) + 's'
  return (ms / 60_000).toFixed(1) + 'min'
}
function formatStepMs(ms?: number) {
  if (!ms) return ''
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}
function truncate(s: string, n: number) { return s.length > n ? s.slice(0, n) + '…' : s }

/** 输出格式化：JSON 美化 + HTML escape */
function formatOutput(raw: string, _role: string) {
  if (!raw) return ''
  let pretty = raw
  const trimmed = raw.trim()
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) ||
      (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try { pretty = JSON.stringify(JSON.parse(trimmed), null, 2) } catch {}
  }
  pretty = pretty.length > 1200 ? pretty.slice(0, 1200) + '\n…（已截断）' : pretty
  return pretty
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
}

function goBack() { router.push('/crew') }

// ─────────────────────────────────────────────
// Time-Travel · Fork
// ─────────────────────────────────────────────
const userStore = useUserStore()

const forkDialog = reactive({
  open: false,
  step: null as AgentStep | null,
  editedOutput: '',
  editSummary: '',
  submitting: false,
})

const forkRun = reactive({
  open: false,
  taskId: 0,
  progress: 0,
  currentRole: '' as string,
  statusText: '准备启动…',
  source: null as EventSource | null,
})

function canFork(s: AgentStep): boolean {
  if (!task.value) return false
  if (s.status !== 'DONE') return false
  // 只允许对已完成 / 失败的任务做 Fork
  const allowedTaskStatus = ['COMPLETED', 'FAILED']
  return allowedTaskStatus.includes(task.value.status)
}

function roleLabelText(role?: string): string {
  if (!role) return ''
  const map: Record<string, string> = {
    PLANNER: 'Planner · 任务规划',
    RESEARCHER: 'Researcher · 调研',
    WRITER: 'Writer · 撰写',
    CRITIC: 'Critic · 评审',
  }
  return map[role.toUpperCase()] || role
}

const forkHintText = computed(() => {
  const role = (forkDialog.step?.agentRole || '').toUpperCase()
  switch (role) {
    case 'PLANNER':
      return '编辑 Plan JSON 后，N 个 Researcher 将基于新子任务并行重新调研，再由 Writer 与 Critic 重跑'
    case 'RESEARCHER':
      return '编辑 Finding 后，其他并行 Researcher 保留，Writer 用合并后的发现重写，Critic 再评审'
    case 'WRITER':
      return '编辑报告后，Critic 将基于新报告重新评分（不重新检索）'
    case 'CRITIC':
      return '编辑评分 JSON 后直接收尾。若 passed=false 且未超重写上限，将触发 Writer 重写一轮'
    default:
      return ''
  }
})

function openForkDialog(s: AgentStep) {
  forkDialog.step = s
  forkDialog.editedOutput = prettifyForEditor(s.output || '')
  forkDialog.editSummary = ''
  forkDialog.open = true
}

function closeForkDialog() {
  if (forkDialog.submitting) return
  forkDialog.open = false
  forkDialog.step = null
}

function prettifyForEditor(raw: string): string {
  if (!raw) return ''
  const t = raw.trim()
  if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
    try { return JSON.stringify(JSON.parse(t), null, 2) } catch { /* fallthrough */ }
  }
  return raw
}

async function submitFork() {
  if (!forkDialog.step) return
  const stepIdx = forkDialog.step.stepIndex
  forkDialog.submitting = true
  try {
    const res: any = await crewApi.forkTask(taskId, {
      fromStepIndex: stepIdx,
      editedOutput: forkDialog.editedOutput,
      editSummary: forkDialog.editSummary || undefined,
    })
    const data = res?.data ?? res
    const newId = data.taskId
    if (!newId) throw new Error('Fork 创建失败：未返回 taskId')

    forkDialog.open = false
    forkDialog.submitting = false

    // 启动进度蒙层并订阅 SSE
    forkRun.taskId = newId
    forkRun.progress = 0
    forkRun.currentRole = ''
    forkRun.statusText = '正在启动 Fork…'
    forkRun.open = true

    const token = userStore.token || ''
    const source = crewApi.streamFork(newId, token)
    forkRun.source = source

    source.addEventListener('task.start', () => {
      forkRun.statusText = '正在恢复历史步骤…'
    })
    source.addEventListener('fork.replay-step', (e: any) => {
      try {
        const d = JSON.parse(e.data)
        forkRun.currentRole = (d.role || '').toUpperCase()
        forkRun.statusText = `恢复 #${d.stepIndex} · ${roleLabelText(d.role)}`
      } catch { /* ignore */ }
    })
    source.addEventListener('agent.start', (e: any) => {
      try {
        const d = JSON.parse(e.data)
        forkRun.currentRole = (d.role || '').toUpperCase()
        forkRun.statusText = `${roleLabelText(d.role)} 启动`
        if (typeof d.progress === 'number') forkRun.progress = d.progress
      } catch { /* ignore */ }
    })
    source.addEventListener('researcher.finding', (e: any) => {
      try {
        const d = JSON.parse(e.data)
        const f = d.data?.finding
        if (f) forkRun.statusText = `Researcher 完成：${f.title || ''}`
      } catch { /* ignore */ }
    })
    source.addEventListener('writer.token', () => {
      forkRun.statusText = 'Writer 正在撰写…'
    })
    source.addEventListener('critic.review', () => {
      forkRun.statusText = 'Critic 已完成评审'
    })
    source.addEventListener('task.done', () => {
      forkRun.progress = 1
      forkRun.statusText = '完成！跳转到新 Fork 任务回放…'
      source.close()
      forkRun.source = null
      setTimeout(() => {
        forkRun.open = false
        router.replace(`/crew/replay/${newId}`)
      }, 700)
    })
    source.addEventListener('task.failed', (e: any) => {
      let msg = 'Fork 任务执行失败'
      try { msg += '：' + (JSON.parse(e.data).data?.error || '') } catch { /* ignore */ }
      ElMessage.error(msg)
      source.close()
      forkRun.source = null
      forkRun.open = false
    })
    source.onerror = () => {
      ElMessage.warning('SSE 连接异常，请到任务列表查看 Fork 状态')
      source.close()
      forkRun.source = null
      forkRun.open = false
    }
  } catch (e: any) {
    forkDialog.submitting = false
    ElMessage.error('Fork 创建失败：' + (e?.message || ''))
  }
}

function dismissForkRun() {
  // 关闭蒙层但保留 SSE 在后台跑（任务仍在后端执行）
  forkRun.open = false
}

// ─── 生命周期 ───
onMounted(load)
onBeforeUnmount(() => {
  pause()
  if (forkRun.source) { try { forkRun.source.close() } catch { /* ignore */ } }
})
</script>

<style scoped>
/* ─────────────────────────────────────────────
   页面骨架
   ───────────────────────────────────────────── */
.replay-page {
  height: 100%;
  display: grid;
  grid-template-rows: auto auto 1fr auto;
  background: var(--bg-page);
  overflow: hidden;
}

/* ─────────────────────────────────────────────
   Hero
   ───────────────────────────────────────────── */
.hero {
  position: relative;
  padding: 26px 32px 20px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--line);
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 20px;
  padding: 7px 16px 7px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.01em;
  cursor: pointer;
  transition: all 220ms var(--ease);
  box-shadow: var(--shadow-xs);
}
.back:hover {
  color: var(--brand);
  border-color: var(--brand-soft-2);
  background: var(--brand-soft);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-sm);
  transform: translateX(-2px);
}
.back:active {
  transform: translateX(-1px) scale(0.98);
}
.back :deep(.el-icon) {
  color: currentColor;
  transition: transform 220ms var(--ease);
}
.back:hover :deep(.el-icon) {
  transform: translateX(-3px);
}

.hero-tags { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.kbd-tag {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.12em;
  padding: 3px 8px;
  border-radius: 4px;
  background: var(--ink-1);
  color: #fff;
}
.status-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.status-tag.completed { background: var(--success-soft); color: var(--success-ink); }
.status-tag.failed    { background: var(--danger-soft);  color: var(--danger-ink); }
.time-tag {
  font-size: 11.5px;
  color: var(--ink-3);
  font-family: 'JetBrains Mono', monospace;
}

.hero-query {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.022em;
  line-height: 1.35;
  margin-bottom: 14px;
}

.hero-stats { display: flex; align-items: baseline; gap: 22px; }
.stat { display: flex; align-items: baseline; gap: 6px; }
.stat-val {
  font-family: 'Manrope', 'JetBrains Mono', monospace;
  font-size: 22px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.02em;
}
.stat-val.score { color: var(--brand); }
.stat-lab {
  font-size: 12px;
  color: var(--ink-3);
  font-weight: 500;
}
.stat-sep {
  width: 1px;
  height: 22px;
  background: var(--line);
  align-self: center;
}

/* ─────────────────────────────────────────────
   4 Agent 泳道
   ───────────────────────────────────────────── */
.lanes {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  padding: 18px 32px;
  background: linear-gradient(180deg, var(--bg-surface), var(--bg-page));
  border-bottom: 1px solid var(--line);
}
.lane {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px 12px;
  box-shadow: var(--shadow-card);
  transition: var(--transition);
}
.lane.active {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-md);
  transform: translateY(-1px);
}
.lane-head { display: flex; align-items: center; gap: 10px; }
.lane-badge {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid;
  font-size: 16px;
  flex-shrink: 0;
}
.lane-meta { flex: 1; min-width: 0; }
.lane-name {
  font-family: 'Manrope', sans-serif;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.01em;
}
.lane-duty {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: 1px;
}
.lane-stat-mini {
  text-align: right;
  flex-shrink: 0;
}
.lane-count {
  font-family: 'Manrope', sans-serif;
  font-size: 18px;
  font-weight: 800;
  color: var(--ink-1);
  display: block;
  line-height: 1;
}
.lane-count-lbl {
  font-size: 10.5px;
  color: var(--ink-3);
  margin-top: 2px;
}

.lane-dots {
  display: flex;
  gap: 5px;
  margin-top: 12px;
  flex-wrap: wrap;
  min-height: 16px;
}
.dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 1.5px solid var(--line-strong);
  background: var(--bg-surface);
  cursor: pointer;
  transition: all 180ms var(--ease);
}
.dot:hover { transform: scale(1.18); }
.dot.done    { background: currentColor; border-color: currentColor; }
.dot.now     { background: #fff; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-glow); animation: pulse-soft 1.4s ease-in-out infinite; }
.dot.fail    { background: var(--danger); border-color: var(--danger); }
.dot.pending { opacity: 0.45; }
.lane[data-role] .dot.done { color: currentColor; }
.lane:nth-child(1) .dot { color: #3D5AFE; }
.lane:nth-child(2) .dot { color: #0369A1; }
.lane:nth-child(3) .dot { color: #047857; }
.lane:nth-child(4) .dot { color: #B45309; }

/* ─────────────────────────────────────────────
   时间线
   ───────────────────────────────────────────── */
.content { overflow: hidden; padding: 0 32px; }
.timeline {
  height: 100%;
  overflow-y: auto;
  padding: 28px 0 40px;
  position: relative;
}
.timeline::before {
  content: '';
  position: absolute;
  left: 22px;
  top: 28px;
  bottom: 40px;
  width: 2px;
  background: linear-gradient(180deg, var(--line), var(--line) 60%, transparent);
  z-index: 0;
}

.step {
  position: relative;
  display: grid;
  grid-template-columns: 46px 1fr;
  gap: 14px;
  margin-bottom: 14px;
  cursor: pointer;
  opacity: 1;
  transition: opacity 240ms var(--ease), transform 240ms var(--ease);
}
.step.future { opacity: 0.42; }
.step.future:hover { opacity: 0.7; }
.step.now {
  opacity: 1;
}

.step-rail {
  position: relative;
  display: flex;
  justify-content: center;
  padding-top: 14px;
}
.rail-node {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--line-strong);
  background: var(--bg-surface);
  z-index: 1;
  transition: all 240ms var(--ease);
}

.step-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 12px 16px 14px;
  box-shadow: var(--shadow-xs);
  transition: all 240ms var(--ease);
}
.step.now .step-card {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-md);
}
.step.past .step-card { background: var(--bg-surface); }
.step.failed .step-card { border-color: var(--danger); }

.step-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.step-num {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: var(--ink-4);
  font-weight: 700;
}
.step-role {
  font-family: 'Manrope', sans-serif;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  padding: 2px 8px;
  border-radius: 4px;
}
.step-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-1);
  flex: 1;
}
.step-status {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: var(--radius-pill);
  background: var(--bg-subtle);
  color: var(--ink-3);
}
.step-status.done    { background: var(--success-soft); color: var(--success-ink); }
.step-status.failed  { background: var(--danger-soft);  color: var(--danger-ink); }
.step-status.running { background: var(--brand-soft);   color: var(--brand-ink); }
.step-time {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: var(--ink-3);
}

.step-body { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.step-block {
  background: var(--bg-subtle);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
}
.step-block.error {
  background: var(--danger-soft);
  color: var(--danger-ink);
}
.block-lbl {
  font-size: 10.5px;
  color: var(--ink-3);
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  margin-bottom: 4px;
}
.block-val {
  font-size: 13px;
  color: var(--ink-1);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.block-val.mono   { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.block-val.output {
  max-height: 240px;
  overflow-y: auto;
  background: #0F1A33;
  color: #DCE3F2;
  border-radius: var(--radius-xs);
  padding: 10px 12px;
  margin: 0;
}

/* 结束标记 */
.finish {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 30px 0 60px;
  margin-left: 46px;
}
.finish-ring {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-hover), var(--brand));
  box-shadow: 0 0 0 6px var(--brand-soft);
}
.finish-text {
  font-family: 'Manrope', sans-serif;
  font-weight: 700;
  color: var(--brand-ink);
  letter-spacing: -0.01em;
  font-size: 15px;
}

/* ─────────────────────────────────────────────
   Empty
   ───────────────────────────────────────────── */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: var(--ink-3);
  gap: 16px;
}
.empty-ring {
  width: 64px;
  height: 64px;
  border: 3px solid var(--line);
  border-radius: 50%;
  border-top-color: var(--brand);
}

/* ─────────────────────────────────────────────
   底部控制
   ───────────────────────────────────────────── */
.controls {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 32px;
  background: var(--bg-surface);
  border-top: 1px solid var(--line);
  box-shadow: 0 -4px 12px rgba(11, 20, 38, 0.04);
}
.ctrl-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
  color: var(--ink-2);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: var(--transition);
}
.ctrl-btn:hover:not(:disabled) {
  background: var(--brand-soft);
  color: var(--brand);
}
.ctrl-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.play-btn {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: linear-gradient(180deg, var(--brand-hover), var(--brand));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-brand);
  transition: var(--transition);
}
.play-btn:hover { filter: brightness(1.06); }
.play-btn:active { filter: brightness(0.95); }

.scrubber {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 8px;
}
.scrubber-track {
  flex: 1;
  height: 6px;
  background: var(--line);
  border-radius: 3px;
  position: relative;
  cursor: pointer;
}
.scrubber-fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: linear-gradient(90deg, var(--brand-hover), var(--brand));
  border-radius: 3px;
  transition: width 180ms var(--ease);
}
.scrubber-thumb {
  position: absolute;
  top: 50%;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--brand);
  transform: translate(-50%, -50%);
  box-shadow: var(--shadow-sm);
  transition: left 180ms var(--ease);
  pointer-events: none;
}
.scrubber-label {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--ink-3);
  font-weight: 600;
  white-space: nowrap;
  min-width: 58px;
  text-align: right;
}

.speed-group {
  display: flex;
  gap: 4px;
  background: var(--bg-subtle);
  border-radius: var(--radius-sm);
  padding: 3px;
}
.speed-btn {
  padding: 5px 10px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-3);
  border-radius: calc(var(--radius-sm) - 2px);
  transition: var(--transition);
}
.speed-btn.active { background: var(--bg-surface); color: var(--brand); box-shadow: var(--shadow-xs); }
.speed-btn:hover:not(.active) { color: var(--ink-1); }

/* Scrollbar inside output blocks */
.output::-webkit-scrollbar { width: 6px; }
.output::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.18); border-radius: 6px; }

/* ─────────────────────────────────────────────
   Time-Travel · Fork 按钮（每步底部）
   ───────────────────────────────────────────── */
.step-actions {
  margin-top: 6px;
  display: flex;
  justify-content: flex-end;
}
.fork-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px 5px 9px;
  border-radius: 8px;
  background: transparent;
  border: 1px dashed var(--line-strong);
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 180ms var(--ease);
}
.fork-btn .fork-hint {
  font-size: 10.5px;
  color: var(--ink-4);
  font-weight: 500;
  padding-left: 4px;
  border-left: 1px solid var(--line);
  margin-left: 2px;
}
.fork-btn:hover {
  border-style: solid;
  border-color: var(--brand);
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.fork-btn:hover .fork-hint { color: var(--brand); border-left-color: var(--brand-soft-2); }
</style>

<style>
/* ─────────────────────────────────────────────
   Fork Modal（teleport 到 body，不能用 scoped）
   ───────────────────────────────────────────── */
.fork-modal {
  position: fixed;
  inset: 0;
  background: rgba(11, 20, 38, 0.42);
  backdrop-filter: blur(6px);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  animation: fade-in 200ms ease;
}
@keyframes fade-in { from { opacity: 0; } to { opacity: 1; } }

.fork-card {
  width: min(680px, 100%);
  max-height: calc(100vh - 64px);
  background: var(--bg-surface);
  border-radius: 18px;
  border: 1px solid var(--line);
  box-shadow: 0 40px 90px rgba(11, 20, 38, 0.18), 0 12px 28px rgba(11, 20, 38, 0.08);
  padding: 22px 24px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}

.fork-head { display: flex; align-items: flex-start; gap: 12px; }
.fork-head-l { flex: 1; }
.fork-kbd {
  display: inline-block;
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.14em;
  padding: 3px 8px;
  border-radius: 4px;
  background: linear-gradient(135deg, #6B8AFF, #3D5AFE);
  color: #fff;
  margin-bottom: 8px;
}
.fork-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.018em;
  line-height: 1.35;
  margin: 0;
}
.fork-sub {
  font-size: 12.5px;
  color: var(--ink-3);
  margin: 4px 0 0;
}
.fork-close {
  width: 28px; height: 28px;
  border-radius: 8px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 180ms ease;
}
.fork-close:hover { background: var(--bg-hover); color: var(--ink-1); }

.fork-hint-box {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--brand-soft);
  border: 1px solid var(--brand-soft-2);
  border-radius: 10px;
  font-size: 12.5px;
  color: var(--brand-ink);
  line-height: 1.55;
}

.fork-lbl {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--ink-3);
  text-transform: uppercase;
  margin-top: 4px;
}

.fork-textarea {
  width: 100%;
  background: #0F1A33;
  color: #DCE3F2;
  border: 1px solid #1B2746;
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 12.5px;
  font-family: 'JetBrains Mono', monospace;
  line-height: 1.6;
  resize: vertical;
  min-height: 220px;
  max-height: 380px;
  outline: none;
  transition: border-color 180ms ease;
}
.fork-textarea:focus { border-color: #3D5AFE; box-shadow: 0 0 0 3px rgba(61,90,254,0.25); }

.fork-input {
  width: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  color: var(--ink-1);
  outline: none;
  transition: all 180ms ease;
}
.fork-input:focus { border-color: var(--brand); box-shadow: 0 0 0 4px var(--brand-glow); }
.fork-input::placeholder { color: var(--ink-4); }

.fork-foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }
.fork-cancel {
  padding: 9px 18px;
  border-radius: 10px;
  background: var(--bg-subtle);
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 180ms ease;
}
.fork-cancel:hover { background: var(--bg-hover); }
.fork-submit {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 22px;
  border-radius: 10px;
  background: linear-gradient(180deg, #5570FF, #3D5AFE);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.01em;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(61, 90, 254, 0.32);
  transition: filter 180ms ease;
}
.fork-submit:hover:not(:disabled) { filter: brightness(1.06); }
.fork-submit:disabled { opacity: 0.55; cursor: not-allowed; }
.fork-submit .spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ─────────────────────────────────────────────
   Fork 执行进度蒙层
   ───────────────────────────────────────────── */
.fork-running {
  position: fixed;
  inset: 0;
  background: rgba(11, 20, 38, 0.62);
  backdrop-filter: blur(10px);
  z-index: 1999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  animation: fade-in 240ms ease;
}
.fr-card {
  background: var(--bg-surface);
  border-radius: 18px;
  border: 1px solid var(--line);
  box-shadow: 0 40px 90px rgba(11, 20, 38, 0.30);
  padding: 28px 30px 22px;
  width: min(420px, 100%);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}
.fr-spinner {
  width: 48px;
  height: 48px;
  border: 3px solid var(--line);
  border-top-color: var(--brand);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}
.fr-title {
  font-family: 'Manrope', sans-serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-1);
  margin: 4px 0 0;
}
.fr-status {
  font-size: 12.5px;
  color: var(--ink-3);
  margin: 0;
  min-height: 18px;
}
.fr-progress {
  width: 100%;
  height: 6px;
  background: var(--line);
  border-radius: 3px;
  overflow: hidden;
  margin-top: 4px;
}
.fr-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #5570FF, #3D5AFE);
  border-radius: 3px;
  transition: width 220ms ease;
}
.fr-lanes {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
  justify-content: center;
}
.fr-lane {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px;
  border-radius: 999px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  font-size: 11.5px;
  font-weight: 600;
  transition: all 180ms ease;
}
.fr-lane.active {
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.fr-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
}
.fr-cancel {
  margin-top: 6px;
  padding: 7px 16px;
  border-radius: 999px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}
.fr-cancel:hover { background: var(--bg-hover); color: var(--ink-1); }
</style>
