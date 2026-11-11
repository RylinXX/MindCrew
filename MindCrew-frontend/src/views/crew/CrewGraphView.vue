<template>
  <div class="graph-page">
    <!-- 顶部条 -->
    <header class="topbar">
      <button class="back" @click="goBack">
        <el-icon :size="14"><Back /></el-icon><span>返回</span>
      </button>

      <div class="topbar-main" v-if="task">
        <span class="kbd-tag">AGENT COMM GRAPH</span>
        <span class="task-query">{{ task.query }}</span>
      </div>

      <div class="topbar-stats" v-if="task">
        <div class="ts-cell">
          <span class="ts-val">{{ task.totalSteps || steps.length }}</span>
          <span class="ts-lbl">节点</span>
        </div>
        <div class="ts-cell">
          <span class="ts-val">{{ linkCount }}</span>
          <span class="ts-lbl">通信</span>
        </div>
        <div class="ts-cell" v-if="task.elapsedMs">
          <span class="ts-val">{{ formatDuration(task.elapsedMs) }}</span>
          <span class="ts-lbl">耗时</span>
        </div>
      </div>
    </header>

    <!-- 主舞台 -->
    <main class="stage">
      <!-- 背景星空层 -->
      <div class="starfield">
        <span v-for="i in 60" :key="i" class="star" :style="starStyle(i)"></span>
      </div>

      <!-- 角色色环 -->
      <div class="role-orbits">
        <div class="orbit" v-for="o in 3" :key="o"></div>
      </div>

      <!-- ECharts 图 -->
      <div ref="chartRef" class="echart"></div>

      <!-- 左下角图例 -->
      <div class="legend">
        <div class="legend-title">AGENT 角色</div>
        <div class="legend-list">
          <div v-for="r in roles" :key="r.role" class="legend-row">
            <span class="legend-dot" :style="{ background: r.color, boxShadow: `0 0 8px ${r.color}` }"></span>
            <span class="legend-name">{{ r.label }}</span>
            <span class="legend-count">×{{ countByRole(r.role) }}</span>
          </div>
        </div>
      </div>

      <!-- 右侧详情面板 -->
      <aside class="detail-panel" :class="{ open: selected }">
        <div class="detail-empty" v-if="!selected">
          <div class="empty-ring"></div>
          <div class="empty-text">
            <p>点击节点查看 Agent 步骤详情</p>
            <p class="empty-hint">支持拖拽节点 · 鼠标滚轮缩放 · 双击重置</p>
          </div>
        </div>

        <template v-else>
          <header class="detail-head">
            <div class="detail-role" :style="{ background: selectedColor + '22', color: selectedColor }">
              <component :is="selectedIcon" />
            </div>
            <div class="detail-meta">
              <div class="detail-role-name">{{ selectedRoleLabel }}</div>
              <div class="detail-step-name">{{ selected.stepName }}</div>
            </div>
            <button class="detail-close" @click="selected = null">
              <el-icon :size="14"><Close /></el-icon>
            </button>
          </header>

          <div class="detail-tags">
            <span class="d-tag">#{{ selected.stepIndex }}</span>
            <span class="d-tag" :class="selected.status.toLowerCase()">{{ statusText(selected.status) }}</span>
            <span class="d-tag mono">{{ formatStepMs(selected.elapsedMs) }}</span>
          </div>

          <div class="detail-body">
            <div v-if="selected.subtask" class="d-block">
              <div class="d-lbl">子任务</div>
              <div class="d-val">{{ selected.subtask }}</div>
            </div>
            <div v-if="selected.input" class="d-block">
              <div class="d-lbl">输入</div>
              <div class="d-val mono">{{ truncate(selected.input, 300) }}</div>
            </div>
            <div v-if="selected.output" class="d-block">
              <div class="d-lbl">输出</div>
              <pre class="d-val output mono" v-text="formatOutput(selected.output)"></pre>
            </div>
            <div v-if="selected.errorMsg" class="d-block error">
              <div class="d-lbl">错误</div>
              <div class="d-val">{{ selected.errorMsg }}</div>
            </div>
          </div>

          <footer class="detail-foot">
            <button class="d-btn" @click="goReplay">
              <el-icon :size="13"><VideoPlay /></el-icon>
              <span>在回放中查看</span>
            </button>
          </footer>
        </template>
      </aside>
    </main>

    <!-- 加载 -->
    <div class="loading" v-if="loading">
      <div class="loading-ring"></div>
      <p>正在解析 Agent 通信链路…</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, Close, VideoPlay, Aim, Search, EditPen, Stamp } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, GraphicComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { crewApi, type AgentTask, type AgentStep } from '@/api/crew'

echarts.use([GraphChart, TooltipComponent, GraphicComponent, CanvasRenderer])

const route  = useRoute()
const router = useRouter()
const taskId = Number(route.params.taskId)

const loading  = ref(true)
const task     = ref<AgentTask | null>(null)
const steps    = ref<AgentStep[]>([])
const selected = ref<AgentStep | null>(null)
const linkCount = ref(0)

const chartRef = ref<HTMLElement | null>(null)
const chart    = shallowRef<echarts.ECharts | null>(null)

// ─────────── 角色配置 ───────────
const roles = [
  { role: 'PLANNER',    label: 'Planner · 任务规划',   color: '#6B8AFF', icon: Aim },
  { role: 'RESEARCHER', label: 'Researcher · 并行调研', color: '#22D3EE', icon: Search },
  { role: 'WRITER',     label: 'Writer · 报告撰写',    color: '#34D399', icon: EditPen },
  { role: 'CRITIC',     label: 'Critic · 质量评审',    color: '#FBBF24', icon: Stamp },
]
const colorByRole = Object.fromEntries(roles.map(r => [r.role, r.color]))

const selectedColor = computed(() => selected.value ? colorByRole[selected.value.agentRole] : '#fff')
const selectedIcon = computed(() => {
  const r = roles.find(x => x.role === selected.value?.agentRole)
  return r?.icon || Aim
})
const selectedRoleLabel = computed(() => {
  const r = roles.find(x => x.role === selected.value?.agentRole)
  return r?.label.split(' · ')[0] || selected.value?.agentRole
})

// ─────────── 数据加载 ───────────
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
    await nextTick()
    renderGraph()
  } catch (e) {
    ElMessage.error('加载任务失败')
  } finally {
    loading.value = false
  }
}

// ─────────── 构造 nodes & links ───────────
function buildGraphData() {
  const nodes: any[] = []
  const links: any[] = []

  const planners    = steps.value.filter(s => s.agentRole === 'PLANNER')
  const researchers = steps.value.filter(s => s.agentRole === 'RESEARCHER')
  const writers     = steps.value.filter(s => s.agentRole === 'WRITER')
  const critics     = steps.value.filter(s => s.agentRole === 'CRITIC')

  // Planner 节点（通常 1 个）
  planners.forEach((s) => {
    nodes.push(buildNode(s, 'PLANNER', 60))
  })

  // Researcher 节点（N 个并行）
  researchers.forEach((s) => {
    nodes.push(buildNode(s, 'RESEARCHER', 44, shortLabel(s.subtask || s.stepName)))
    // 边：Planner → 每个 Researcher
    planners.forEach(p => {
      links.push({
        source: nodeId(p),
        target: nodeId(s),
        value: '子任务下发',
      })
    })
  })

  // Writer 节点（可能 1-2 个：原稿 + 重写）
  writers.forEach((s, i) => {
    nodes.push(buildNode(s, 'WRITER', 54, i === 0 ? 'Writer' : 'Writer · 重写'))
    if (i === 0) {
      // 所有 Researcher → 首个 Writer
      researchers.forEach(r => {
        links.push({
          source: nodeId(r),
          target: nodeId(s),
          value: '调研结论',
        })
      })
    } else {
      // 上一轮 Critic → 重写的 Writer
      const prevCritic = critics[i - 1]
      if (prevCritic) {
        links.push({
          source: nodeId(prevCritic),
          target: nodeId(s),
          value: '重写反馈',
          lineStyle: { type: 'dashed' },
        })
      }
    }
  })

  // Critic 节点
  critics.forEach((s, i) => {
    nodes.push(buildNode(s, 'CRITIC', 54, i === 0 ? 'Critic' : 'Critic · 再审'))
    const correspondingWriter = writers[i]
    if (correspondingWriter) {
      links.push({
        source: nodeId(correspondingWriter),
        target: nodeId(s),
        value: '报告评审',
      })
    }
  })

  linkCount.value = links.length
  return { nodes, links }
}

function buildNode(s: AgentStep, role: string, size: number, label?: string) {
  const color = colorByRole[role]
  return {
    id: nodeId(s),
    name: label || roleLabelShort(role),
    symbolSize: size,
    category: role,
    value: s.elapsedMs,
    rawStep: s,
    itemStyle: {
      color: color,
      shadowBlur: 20,
      shadowColor: color,
      borderColor: '#fff',
      borderWidth: 1.5,
    },
    label: {
      show: true,
      position: 'bottom',
      color: '#E6EAF6',
      fontSize: 11,
      fontWeight: 600,
      distance: 8,
      formatter: (p: any) => p.name,
    },
  }
}

function nodeId(s: AgentStep) { return `${s.agentRole}-${s.stepIndex}` }
function roleLabelShort(r: string) {
  return { PLANNER: 'Planner', RESEARCHER: 'Researcher', WRITER: 'Writer', CRITIC: 'Critic' }[r] || r
}
function shortLabel(s: string, n = 14) {
  if (!s) return 'Researcher'
  return s.length > n ? s.slice(0, n) + '…' : s
}

// ─────────── 渲染图表 ───────────
function renderGraph() {
  if (!chartRef.value) return
  chart.value?.dispose()
  chart.value = echarts.init(chartRef.value, undefined, { renderer: 'canvas' })

  const { nodes, links } = buildGraphData()

  const option: any = {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: 'rgba(15, 23, 42, 0.96)',
      borderColor: 'rgba(255,255,255,0.08)',
      borderWidth: 1,
      textStyle: { color: '#fff', fontSize: 12 },
      padding: [10, 14],
      formatter: (p: any) => {
        if (p.dataType === 'edge') {
          return `<span style="color:#94A0BD">${p.data.value}</span>`
        }
        const s: AgentStep = p.data.rawStep
        if (!s) return p.name
        const color = colorByRole[s.agentRole]
        return `
          <div style="font-weight:700;color:${color};margin-bottom:4px">
            ${roleLabelShort(s.agentRole)} · #${s.stepIndex}
          </div>
          <div style="color:#E6EAF6;margin-bottom:2px">${s.stepName}</div>
          <div style="color:#94A0BD;font-size:11px">耗时 ${formatStepMs(s.elapsedMs)}</div>
        `
      },
    },
    animationDuration: 1400,
    animationEasingUpdate: 'quinticInOut',
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      categories: roles.map(r => ({ name: r.role })),
      force: {
        repulsion: 320,
        edgeLength: [110, 180],
        gravity: 0.08,
        layoutAnimation: true,
      },
      lineStyle: {
        color: 'target',
        width: 1.6,
        opacity: 0.55,
        curveness: 0.18,
        shadowBlur: 8,
        shadowColor: 'rgba(120, 160, 255, 0.4)',
      },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 10],
      edgeLabel: {
        show: false,
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 3, opacity: 1 },
        itemStyle: { shadowBlur: 30 },
        label: { color: '#fff', fontSize: 13 },
      },
      data: nodes,
      links: links,
    }],
  }

  chart.value.setOption(option)

  chart.value.on('click', (params: any) => {
    if (params.dataType === 'node' && params.data?.rawStep) {
      selected.value = params.data.rawStep
    }
  })

  chart.value.on('dblclick', () => {
    chart.value?.dispatchAction({ type: 'restore' })
  })

  // 响应窗口缩放
  window.addEventListener('resize', resize)
}

function resize() { chart.value?.resize() }

// ─────────── UI 辅助 ───────────
function countByRole(role: string) { return steps.value.filter(s => s.agentRole === role).length }
function statusText(s: string) { return s === 'DONE' ? '已完成' : s === 'FAILED' ? '失败' : s === 'RUNNING' ? '进行中' : '已跳过' }
function formatStepMs(ms?: number) {
  if (!ms) return '—'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}
function formatDuration(ms?: number) {
  if (!ms) return '—'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60_000) return (ms / 1000).toFixed(1) + 's'
  return (ms / 60_000).toFixed(1) + 'min'
}
function truncate(s: string, n: number) { return s.length > n ? s.slice(0, n) + '…' : s }
function formatOutput(raw: string) {
  if (!raw) return ''
  let pretty = raw
  const t = raw.trim()
  if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
    try { pretty = JSON.stringify(JSON.parse(t), null, 2) } catch {}
  }
  return pretty.length > 1500 ? pretty.slice(0, 1500) + '\n…（已截断）' : pretty
}
function starStyle(i: number) {
  const seed = (i * 7919) % 100
  const top = (i * 31) % 100
  const left = ((i * 53) + 17) % 100
  const dur = 3 + (seed % 4)
  const delay = (seed % 7) * 0.4
  const size = 1 + (seed % 3) * 0.6
  return {
    top: top + '%',
    left: left + '%',
    width: size + 'px',
    height: size + 'px',
    animationDuration: dur + 's',
    animationDelay: -delay + 's',
  }
}

function goBack()   { router.push('/crew') }
function goReplay() { if (taskId) router.push(`/crew/replay/${taskId}`) }

// ─────────── 生命周期 ───────────
onMounted(load)
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart.value?.dispose()
})
</script>

<style scoped>
/* ─────────────────────────────────────────────
   命令中心：暗色作战面板风
   ───────────────────────────────────────────── */
.graph-page {
  position: relative;
  height: 100%;
  display: grid;
  grid-template-rows: auto 1fr;
  background:
    radial-gradient(900px 600px at 50% 0%, rgba(61, 90, 254, 0.20), transparent 60%),
    radial-gradient(700px 500px at 100% 100%, rgba(14, 165, 233, 0.14), transparent 60%),
    linear-gradient(180deg, #060A18 0%, #0A1226 50%, #0F1B33 100%);
  color: #E6EAF6;
  overflow: hidden;
}

/* ── 顶部 ── */
.topbar {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px 28px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(15, 27, 51, 0.45);
  backdrop-filter: blur(20px);
  z-index: 5;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #C6CEE5;
  font-size: 12.5px;
  font-weight: 500;
  transition: var(--transition);
}
.back:hover { background: rgba(255, 255, 255, 0.08); color: #fff; border-color: rgba(255, 255, 255, 0.18); }

.topbar-main {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.kbd-tag {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.14em;
  padding: 3px 9px;
  border-radius: 4px;
  background: linear-gradient(135deg, #6B8AFF, #3D5AFE);
  color: #fff;
  flex-shrink: 0;
}
.task-query {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 17px;
  font-weight: 700;
  color: #fff;
  letter-spacing: -0.018em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-stats {
  display: flex;
  align-items: center;
  gap: 22px;
  flex-shrink: 0;
}
.ts-cell { display: flex; align-items: baseline; gap: 6px; }
.ts-val {
  font-family: 'Manrope', 'JetBrains Mono', monospace;
  font-size: 20px;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.01em;
}
.ts-lbl {
  font-size: 11px;
  color: #94A0BD;
  font-weight: 500;
}

/* ── 舞台 ── */
.stage {
  position: relative;
  overflow: hidden;
}

/* 星空层 */
.starfield {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.star {
  position: absolute;
  border-radius: 50%;
  background: #C7D1FF;
  box-shadow: 0 0 6px #6B8AFF;
  opacity: 0;
  animation: twinkle infinite ease-in-out;
}
@keyframes twinkle {
  0%, 100% { opacity: 0; transform: scale(0.6); }
  50%      { opacity: 0.7; transform: scale(1); }
}

/* 色环 */
.role-orbits {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.role-orbits .orbit {
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.04);
}
.role-orbits .orbit:nth-child(1) { width: 36%; height: 56%; }
.role-orbits .orbit:nth-child(2) { width: 56%; height: 78%; }
.role-orbits .orbit:nth-child(3) { width: 78%; height: 100%; }

.echart {
  position: absolute;
  inset: 0;
  z-index: 2;
}

/* ── 图例 ── */
.legend {
  position: absolute;
  left: 24px;
  bottom: 24px;
  z-index: 3;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: rgba(11, 20, 38, 0.66);
  border: 1px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(14px);
  min-width: 220px;
}
.legend-title {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: #94A0BD;
  margin-bottom: 10px;
}
.legend-list { display: flex; flex-direction: column; gap: 7px; }
.legend-row { display: flex; align-items: center; gap: 10px; }
.legend-dot {
  width: 10px; height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.legend-name {
  flex: 1;
  font-size: 12.5px;
  color: #D7DDEC;
  font-weight: 500;
}
.legend-count {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  font-weight: 700;
  color: #94A0BD;
}

/* ── 右侧详情面板 ── */
.detail-panel {
  position: absolute;
  top: 24px;
  right: 24px;
  bottom: 24px;
  width: 380px;
  z-index: 4;
  background: rgba(11, 20, 38, 0.72);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-lg);
  padding: 18px 18px 14px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 30px 60px rgba(0, 0, 0, 0.4);
  transform: translateX(0);
  transition: transform 280ms var(--ease), opacity 280ms var(--ease);
}

.detail-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 16px;
  color: #94A0BD;
}
.empty-ring {
  width: 48px;
  height: 48px;
  border: 2px solid rgba(255, 255, 255, 0.12);
  border-top-color: #6B8AFF;
  border-radius: 50%;
  animation: spin 2.5s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.empty-text p { font-size: 13px; line-height: 1.7; }
.empty-hint {
  margin-top: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px !important;
  color: #6B7B98;
  letter-spacing: 0.02em;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.detail-role {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  flex-shrink: 0;
}
.detail-meta { flex: 1; min-width: 0; }
.detail-role-name {
  font-family: 'Manrope', sans-serif;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  letter-spacing: -0.005em;
}
.detail-step-name {
  font-size: 12px;
  color: #94A0BD;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail-close {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  color: #94A0BD;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: var(--transition);
}
.detail-close:hover { background: rgba(255, 255, 255, 0.12); color: #fff; }

.detail-tags {
  display: flex;
  gap: 6px;
  margin: 12px 0 8px;
  flex-wrap: wrap;
}
.d-tag {
  font-size: 10.5px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.06);
  color: #C6CEE5;
  letter-spacing: 0.04em;
}
.d-tag.mono { font-family: 'JetBrains Mono', monospace; }
.d-tag.done    { background: rgba(52, 211, 153, 0.18); color: #6EE7B7; }
.d-tag.failed  { background: rgba(239, 68, 68, 0.18);  color: #FCA5A5; }
.d-tag.running { background: rgba(107, 138, 255, 0.18); color: #BFD0FF; }

.detail-body {
  flex: 1;
  overflow-y: auto;
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
}
.d-block {
  background: rgba(255, 255, 255, 0.035);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}
.d-block.error { background: rgba(239, 68, 68, 0.10); color: #FECACA; }
.d-lbl {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: #94A0BD;
  margin-bottom: 5px;
}
.d-val {
  font-size: 12.5px;
  line-height: 1.65;
  color: #DCE3F2;
  white-space: pre-wrap;
  word-break: break-word;
}
.d-val.mono { font-family: 'JetBrains Mono', monospace; font-size: 11.5px; }
.d-val.output {
  background: rgba(0, 0, 0, 0.30);
  border-radius: var(--radius-xs);
  padding: 10px 12px;
  margin: 0;
  max-height: 240px;
  overflow-y: auto;
  color: #B7C2DC;
}
.d-val.output::-webkit-scrollbar { width: 5px; }
.d-val.output::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.12); border-radius: 5px; }

.detail-foot {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.d-btn {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: var(--radius-sm);
  background: rgba(107, 138, 255, 0.16);
  color: #BFD0FF;
  font-size: 12.5px;
  font-weight: 600;
  border: 1px solid rgba(107, 138, 255, 0.30);
  transition: var(--transition);
}
.d-btn:hover { background: rgba(107, 138, 255, 0.26); color: #fff; }

/* ── Loading ── */
.loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  background: rgba(6, 10, 24, 0.5);
  backdrop-filter: blur(8px);
  z-index: 100;
  color: #94A0BD;
  font-size: 13px;
}
.loading-ring {
  width: 44px;
  height: 44px;
  border: 3px solid rgba(255, 255, 255, 0.12);
  border-top-color: #6B8AFF;
  border-radius: 50%;
  animation: spin 1.2s linear infinite;
}

@media (max-width: 900px) {
  .detail-panel { width: 92%; right: 4%; left: 4%; top: auto; bottom: 16px; height: 50%; }
  .legend { display: none; }
}
</style>
