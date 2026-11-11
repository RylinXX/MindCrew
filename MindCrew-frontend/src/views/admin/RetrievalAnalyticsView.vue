<template>
  <div class="retrieval-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">检索分析</h2>
          <span class="title-tag">召回质量 · 运营洞察</span>
        </div>
        <p class="page-desc">
          从问答检索日志中聚合命中率、零结果率、兜底率、改写效果、工具分布与知识库维度，
          并列出零结果 / 兜底 / 点踩的低质量查询，支持导出 CSV。
        </p>
      </div>
      <div class="filter-bar">
        <el-radio-group v-model="rangeDays" @change="loadAll">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </header>

    <!-- KPI -->
    <section class="kpi-grid" v-loading="loading">
      <div class="kpi-card" v-for="kpi in kpiCards" :key="kpi.label">
        <span class="kpi-label">{{ kpi.label }}</span>
        <span class="kpi-value" :class="kpi.tone">{{ kpi.value }}</span>
        <span class="kpi-sub">{{ kpi.sub }}</span>
      </div>
    </section>

    <!-- 图表 -->
    <section class="chart-grid">
      <div class="chart-card wide">
        <header class="chart-head">
          <span>问答量与命中率趋势</span>
          <span class="chart-sub">按天 · 双轴</span>
        </header>
        <div ref="trendChartRef" class="chart-body" v-show="trend.length"></div>
        <div v-if="!trend.length" class="chart-empty">所选时间段内暂无检索日志</div>
      </div>

      <div class="chart-card">
        <header class="chart-head">
          <span>工具分布</span>
          <span class="chart-sub">selectedTools</span>
        </header>
        <div ref="toolsChartRef" class="chart-body" v-show="tools.length"></div>
        <div v-if="!tools.length" class="chart-empty">暂无工具调用记录</div>
      </div>

      <div class="chart-card">
        <header class="chart-head">
          <span>知识库使用排行</span>
          <span class="chart-sub">引用次数</span>
        </header>
        <div ref="kbsChartRef" class="chart-body" v-show="kbs.length"></div>
        <div v-if="!kbs.length" class="chart-empty">暂无知识库命中记录</div>
      </div>
    </section>

    <!-- 低质量查询 -->
    <section class="low-quality-card">
      <header class="lq-head">
        <div class="lq-title">
          <h3>低质量查询</h3>
          <span class="lq-desc">点击任一行查看完整 retrieval_log 与自检日志</span>
        </div>
        <div class="lq-actions">
          <el-radio-group v-model="lowType" size="small" @change="reloadLowQuality">
            <el-radio-button value="zero">零结果</el-radio-button>
            <el-radio-button value="fallback">疑似兜底</el-radio-button>
            <el-radio-button value="negative">被点踩</el-radio-button>
          </el-radio-group>
          <el-button size="small" :icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
        </div>
      </header>

      <el-table
        :data="lowQuality.records"
        v-loading="lowQualityLoading"
        size="small"
        style="width: 100%"
        @row-click="openDetail"
      >
        <el-table-column label="问题" min-width="240">
          <template #default="{ row }">
            <div class="q-cell">
              <span class="q-text">{{ row.question || '（原问题缺失）' }}</span>
              <span v-if="row.rewrittenQuery && row.rewrittenQuery !== row.question" class="q-rewrite">
                改写：{{ row.rewrittenQuery }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="召回" width="150">
          <template #default="{ row }">
            <span class="recall">V {{ row.vectorResults }} / B {{ row.bm25Results }} / R {{ row.rrfCount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="rerankTop" label="重排" width="70" />
        <el-table-column label="兜底" width="70">
          <template #default="{ row }">
            <span :class="row.fallback ? 'tag-warn' : 'text-muted'">{{ row.fallback ? '是' : '否' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="反馈" width="70">
          <template #default="{ row }">
            <span v-if="row.feedback > 0" class="tag-ok">👍</span>
            <span v-else-if="row.feedback < 0" class="tag-bad">👎</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="时延" width="90">
          <template #default="{ row }">{{ row.latencyMs ? row.latencyMs + ' ms' : '—' }}</template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" width="130" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>

      <div class="lq-footer">
        <span class="lq-total">共 {{ lowQuality.total }} 条</span>
        <el-pagination
          v-model:current-page="lowQuality.current"
          v-model:page-size="lowQuality.size"
          :total="lowQuality.total"
          :page-sizes="[10, 20, 50]"
          layout="sizes, prev, pager, next"
          small
          background
          @current-change="loadLowQuality"
          @size-change="reloadLowQuality"
        />
      </div>
    </section>

    <!-- 明细抽屉 -->
    <el-drawer v-model="detailVisible" title="检索明细" size="620px" :destroy-on-close="true">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <div class="detail-q">
            <div class="detail-label">问题</div>
            <div class="detail-text">{{ detail.question || '—' }}</div>
          </div>
          <div class="detail-q">
            <div class="detail-label">回答</div>
            <div class="detail-answer">{{ detail.answer || '—' }}</div>
          </div>
          <div class="detail-meta">
            <span>时延 {{ detail.latencyMs }} ms</span>
            <span>反馈 {{ feedbackLabel(detail.feedback) }}</span>
            <span>Message #{{ detail.messageId }}</span>
          </div>

          <div class="detail-block" v-if="detail.retrievalLog">
            <div class="detail-label">retrieval_log</div>
            <pre class="json-box">{{ pretty(detail.retrievalLog) }}</pre>
          </div>
          <div class="detail-block" v-if="detail.reflectionLog">
            <div class="detail-label">reflection_log · 自检日志</div>
            <pre class="json-box">{{ pretty(detail.reflectionLog) }}</pre>
          </div>
          <div class="detail-block" v-if="detail.sources">
            <div class="detail-label">sources · 答案溯源</div>
            <pre class="json-box">{{ pretty(detail.sources) }}</pre>
          </div>
          <div class="detail-block" v-if="detail.agentTrace">
            <div class="detail-label">agent_trace</div>
            <pre class="json-box">{{ pretty(detail.agentTrace) }}</pre>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { retrievalApi } from '@/api/retrieval'
import type {
  LowQualityType,
  RetrievalDetail,
  RetrievalKbStat,
  RetrievalLowQualityPage,
  RetrievalOverview,
  RetrievalToolStat,
  RetrievalTrendPoint
} from '@/api/retrieval'
import { downloadBlob } from '@/utils/download'

const rangeDays = ref(30)
const loading = ref(false)

const overview = ref<RetrievalOverview | null>(null)
const trend = ref<RetrievalTrendPoint[]>([])
const tools = ref<RetrievalToolStat[]>([])
const kbs = ref<RetrievalKbStat[]>([])

const lowType = ref<LowQualityType>('zero')
const lowQualityLoading = ref(false)
const lowQuality = ref<RetrievalLowQualityPage>({ records: [], total: 0, current: 1, size: 20 })
const exporting = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RetrievalDetail | null>(null)

const trendChartRef = ref<HTMLElement | null>(null)
const toolsChartRef = ref<HTMLElement | null>(null)
const kbsChartRef = ref<HTMLElement | null>(null)
let trendChart: echarts.ECharts | null = null
let toolsChart: echarts.ECharts | null = null
let kbsChart: echarts.ECharts | null = null

const TOOL_LABELS: Record<string, string> = {
  doc_search: '文档检索',
  keyword_search: '关键词检索',
  web_search: '联网搜索',
  recall_memory: '召回记忆',
  store_memory: '写入记忆',
  none: '无工具'
}

const percent = (value?: number) => `${((value ?? 0) * 100).toFixed(1)}%`

function formatCost(value?: number, digits = 4) {
  const v = value ?? 0
  if (v <= 0) return '¥0.00'
  return '¥' + v.toFixed(v < 0.01 ? digits : 2)
}

const kpiCards = computed(() => {
  const o = overview.value
  return [
    { label: '回答总数', value: String(o?.totalAnswers ?? 0), sub: `${o?.withLog ?? 0} 条携带检索日志`, tone: '' },
    { label: '检索命中率', value: percent(o?.hitRate), sub: `命中 ${o?.hitCount ?? 0} 条`, tone: 'good' },
    { label: '零结果率', value: percent(o?.zeroResultRate), sub: `${o?.zeroResultCount ?? 0} 条零召回`, tone: 'bad' },
    { label: '疑似兜底率', value: percent(o?.fallbackRate), sub: `${o?.fallbackCount ?? 0} 条疑似兜底`, tone: 'warn' },
    { label: '改写触发率', value: percent(o?.rewriteRate), sub: `${o?.rewriteCount ?? 0} 条触发改写`, tone: 'info' },
    { label: '平均时延', value: `${Math.round(o?.avgLatencyMs ?? 0)} ms`, sub: `平均重排 ${(o?.avgRerank ?? 0).toFixed(1)} 条`, tone: 'info' },
    { label: '满意度', value: percent(o?.satisfactionRate), sub: `👍 ${o?.positiveFeedback ?? 0} · 👎 ${o?.negativeFeedback ?? 0}`, tone: 'good' },
    { label: 'Token 成本', value: formatCost(o?.totalCostCny), sub: `单次均值 ${formatCost(o?.avgCostCny, 4)}`, tone: 'warn' }
  ]
})

function feedbackLabel(value?: number) {
  if (value === 1) return '有用'
  if (value === -1) return '没用'
  return '未评价'
}

function pretty(value: unknown): string {
  if (value == null) return '—'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

// ─────────────────────────────────────────────
// 数据
// ─────────────────────────────────────────────
async function loadAll() {
  loading.value = true
  try {
    const [o, t, tool, kb] = await Promise.all([
      retrievalApi.overview(rangeDays.value),
      retrievalApi.trend(rangeDays.value),
      retrievalApi.tools(rangeDays.value),
      retrievalApi.kbs(rangeDays.value)
    ])
    overview.value = o
    trend.value = t
    tools.value = tool
    kbs.value = kb
    await nextTick()
    renderCharts()
  } catch (e: any) {
    ElMessage.error('加载检索分析失败：' + (e?.message || ''))
  } finally {
    loading.value = false
  }
}

async function loadLowQuality() {
  lowQualityLoading.value = true
  try {
    lowQuality.value = await retrievalApi.lowQuality({
      days: rangeDays.value,
      current: lowQuality.value.current,
      size: lowQuality.value.size,
      type: lowType.value
    })
  } catch (e: any) {
    ElMessage.error('加载低质量查询失败：' + (e?.message || ''))
  } finally {
    lowQualityLoading.value = false
  }
}

function reloadLowQuality() {
  lowQuality.value.current = 1
  loadLowQuality()
}

async function openDetail(row: { messageId: number }) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await retrievalApi.detail(row.messageId)
  } catch (e: any) {
    ElMessage.error('加载明细失败：' + (e?.message || ''))
  } finally {
    detailLoading.value = false
  }
}

async function exportCsv() {
  exporting.value = true
  try {
    const file = await retrievalApi.exportCsv(rangeDays.value, lowType.value)
    downloadBlob(file.blob, file.filename || 'low-quality.csv')
    ElMessage.success('CSV 已导出')
  } catch (e: any) {
    ElMessage.error('导出失败：' + (e?.message || ''))
  } finally {
    exporting.value = false
  }
}

// ─────────────────────────────────────────────
// 图表
// ─────────────────────────────────────────────
function ensureChart(
  instance: echarts.ECharts | null,
  el: HTMLElement | null
): echarts.ECharts | null {
  if (!el) return null
  return instance ?? echarts.init(el)
}

function renderCharts() {
  renderTrend()
  renderTools()
  renderKbs()
}

function renderTrend() {
  const dates = trend.value.map(item => item.date)
  if (!dates.length) {
    trendChart?.clear()
    return
  }
  trendChart = ensureChart(trendChart, trendChartRef.value)
  if (!trendChart) return
  trendChart.clear()
  trendChart.setOption({
    color: ['#3D5AFE', '#10B981', '#F59E0B'],
    tooltip: { trigger: 'axis' },
    legend: { data: ['问答量', '命中率', '疑似兜底'], right: 10, bottom: 0, itemWidth: 10, itemHeight: 6, textStyle: { fontSize: 11, color: '#6B7488' } },
    grid: { left: 44, right: 48, top: 16, bottom: 40 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#E6EAF2' } },
      axisLabel: { fontSize: 10, color: '#8590A6' }
    },
    yAxis: [
      {
        type: 'value',
        name: '问答量',
        nameTextStyle: { fontSize: 10, color: '#8590A6' },
        splitLine: { lineStyle: { color: '#F1F4FA' } },
        axisLabel: { fontSize: 10, color: '#8590A6' }
      },
      {
        type: 'value',
        name: '命中率',
        min: 0,
        max: 1,
        axisLabel: { fontSize: 10, color: '#8590A6', formatter: (v: number) => `${Math.round(v * 100)}%` },
        splitLine: { show: false }
      }
    ],
    series: [
      { name: '问答量', type: 'bar', data: trend.value.map(i => i.questions), barMaxWidth: 18, itemStyle: { borderRadius: [4, 4, 0, 0] } },
      { name: '命中率', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 5, data: trend.value.map(i => i.hitRate) },
      { name: '疑似兜底', type: 'line', smooth: true, symbolSize: 4, lineStyle: { type: 'dashed' }, data: trend.value.map(i => i.fallbackCount) }
    ]
  })
}

function renderTools() {
  if (!tools.value.length) {
    toolsChart?.clear()
    return
  }
  toolsChart = ensureChart(toolsChart, toolsChartRef.value)
  if (!toolsChart) return
  toolsChart.clear()
  toolsChart.setOption({
    color: ['#3D5AFE', '#0EA5E9', '#7C3AED', '#10B981', '#F59E0B', '#94A3B8'],
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 次（{d}%）' },
    legend: { bottom: 0, itemWidth: 9, itemHeight: 9, textStyle: { fontSize: 10.5, color: '#6B7488' } },
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        data: tools.value.map(item => ({
          name: TOOL_LABELS[item.tool] || item.tool,
          value: item.count
        }))
      }
    ]
  })
}

function renderKbs() {
  const top = kbs.value.slice(0, 8)
  if (!top.length) {
    kbsChart?.clear()
    return
  }
  kbsChart = ensureChart(kbsChart, kbsChartRef.value)
  if (!kbsChart) return
  // 横向柱图：反转后使用从下到上递增
  const reversed = [...top].reverse()
  kbsChart.clear()
  kbsChart.setOption({
    color: ['#7C3AED'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 22, top: 10, bottom: 10, containLabel: true },
    xAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#F1F4FA' } },
      axisLabel: { fontSize: 10, color: '#8590A6' }
    },
    yAxis: {
      type: 'category',
      data: reversed.map(item => item.kbName),
      axisLine: { lineStyle: { color: '#E6EAF2' } },
      axisTick: { show: false },
      axisLabel: { fontSize: 10.5, color: '#4B5670', width: 96, overflow: 'truncate' }
    },
    series: [
      {
        type: 'bar',
        data: reversed.map(item => item.count),
        barMaxWidth: 14,
        itemStyle: { borderRadius: [0, 5, 5, 0] },
        label: { show: true, position: 'right', fontSize: 10, color: '#6B7488' }
      }
    ]
  })
}

function handleResize() {
  trendChart?.resize()
  toolsChart?.resize()
  kbsChart?.resize()
}

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  await Promise.all([loadAll(), loadLowQuality()])
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  toolsChart?.dispose()
  kbsChart?.dispose()
  trendChart = null
  toolsChart = null
  kbsChart = null
})
</script>

<style scoped>
.retrieval-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 30px 44px;
  background: var(--bg-page);
}

.page-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  gap: 22px; flex-wrap: wrap; margin-bottom: 18px;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 7px; }
.page-title { font-family: 'Manrope', 'Noto Sans SC', sans-serif; font-size: 23px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #ede9fe, #ddd6fe); color: #6d28d9;
}
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 800px; line-height: 1.65; }
.filter-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.kpi-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 13px 16px;
  display: flex; flex-direction: column; gap: 3px;
  box-shadow: var(--shadow-card);
}
.kpi-label { font-size: 11.5px; color: var(--ink-3); }
.kpi-value { font-family: 'Manrope', 'Noto Sans SC', sans-serif; font-size: 22px; font-weight: 800; color: var(--ink-1); line-height: 1.25; }
.kpi-value.good { color: var(--success-ink); }
.kpi-value.bad { color: var(--danger-ink); }
.kpi-value.warn { color: var(--warning-ink); }
.kpi-value.info { color: var(--info-ink); }
.kpi-sub { font-size: 10.5px; color: var(--ink-4); }

.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.chart-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 14px 16px 10px;
  box-shadow: var(--shadow-card);
  min-height: 230px;
  display: flex;
  flex-direction: column;
}
.chart-card.wide { grid-column: span 2; }
.chart-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: 6px; }
.chart-head > span:first-child { font-size: 13px; font-weight: 700; color: var(--ink-1); }
.chart-sub { font-size: 10.5px; color: var(--ink-4); }
.chart-body { flex: 1; min-height: 190px; width: 100%; }
.chart-empty {
  flex: 1; display: flex; align-items: center; justify-content: center;
  color: var(--ink-4); font-size: 12.5px; min-height: 190px;
}

.low-quality-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px 18px 12px;
  box-shadow: var(--shadow-card);
}
.lq-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; flex-wrap: wrap; margin-bottom: 10px; }
.lq-title h3 { font-size: 15px; font-weight: 700; color: var(--ink-1); }
.lq-desc { font-size: 11.5px; color: var(--ink-4); }
.lq-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.lq-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 10px; }
.lq-total { font-size: 11.5px; color: var(--ink-4); }
.q-cell { display: flex; flex-direction: column; gap: 2px; }
.q-text { color: var(--ink-1); font-size: 12.5px; line-height: 1.5; }
.q-rewrite { color: var(--ink-4); font-size: 11px; line-height: 1.45; }
.recall { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--ink-2); }
.tag-warn { color: var(--warning-ink); font-weight: 600; }
.tag-ok { color: var(--success-ink); }
.tag-bad { color: var(--danger-ink); }
.text-muted { color: var(--ink-4); }
.low-quality-card :deep(.el-table__row) { cursor: pointer; }

.detail-body { display: flex; flex-direction: column; gap: 14px; }
.detail-label { font-size: 11px; font-weight: 700; color: var(--ink-3); letter-spacing: 0.04em; margin-bottom: 4px; }
.detail-text { font-size: 13.5px; color: var(--ink-1); line-height: 1.7; }
.detail-answer {
  font-size: 13px; color: var(--ink-2); line-height: 1.75;
  background: var(--bg-subtle); border: 1px solid var(--line);
  border-radius: 10px; padding: 10px 12px; white-space: pre-wrap; word-break: break-word;
}
.detail-meta { display: flex; gap: 16px; flex-wrap: wrap; font-size: 11.5px; color: var(--ink-3); }
.detail-block { margin-top: 2px; }
.json-box {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px; line-height: 1.6;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  max-height: 280px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ink-2);
}

@media (max-width: 1100px) {
  .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .chart-grid { grid-template-columns: minmax(0, 1fr); }
  .chart-card.wide { grid-column: span 1; }
}
@media (max-width: 720px) {
  .retrieval-page { padding: 18px 16px 34px; }
}
</style>
