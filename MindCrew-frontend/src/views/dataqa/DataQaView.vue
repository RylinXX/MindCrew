<template>
  <div class="dataqa-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">AI 问数</h2>
          <span class="title-tag">自然语言 → 指标 · 只读</span>
        </div>
        <p class="page-desc">
          用一句话问运营数据：提问量、活跃用户、Token 用量、模型成本、响应时延、满意度、知识库排行……
          后端只做白名单指标 + 固定模板查询，不执行 LLM 生成的 SQL。
        </p>
      </div>
    </header>

    <div class="qa-layout">
      <main class="qa-main">
        <!-- 提问区 -->
        <section class="ask-card">
          <div class="ask-row">
            <el-input
              v-model="question"
              size="large"
              class="ask-input"
              placeholder="例如：最近 30 天每个模型的成本"
              :disabled="asking"
              @keyup.enter="ask"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-select v-model="daysOverride" size="large" class="ask-range" :disabled="asking">
              <el-option label="时间智能解析" :value="0" />
              <el-option label="近 7 天" :value="7" />
              <el-option label="近 30 天" :value="30" />
              <el-option label="近 90 天" :value="90" />
            </el-select>
            <el-button type="primary" size="large" :loading="asking" :disabled="!question.trim()" @click="ask">
              问一句
            </el-button>
          </div>

          <div class="example-row" v-if="metrics.length">
            <span class="example-label">试试：</span>
            <button
              v-for="metric in metrics.slice(0, 5)"
              :key="metric.key"
              class="example-chip"
              :disabled="asking"
              @click="fillExample(metric)"
            >{{ metric.example }}</button>
          </div>
        </section>

        <!-- 错误 -->
        <section v-if="error" class="error-card">
          <el-icon :size="20"><WarningFilled /></el-icon>
          <div class="error-body">
            <div class="error-title">{{ error }}</div>
            <div class="error-hint">可以换个问法，或从下方「支持的指标」里选一个示例问题。</div>
          </div>
        </section>

        <!-- 结果 -->
        <template v-if="result">
          <!-- 一句话结论 -->
          <section class="answer-card">
            <div class="answer-head">
              <span class="metric-tag">{{ result.metricName }}</span>
              <span class="answer-meta">
                解析：近 {{ result.interpreted.days }} 天
                <template v-if="result.interpreted.limit">· Top {{ result.interpreted.limit }}</template>
                · 耗时 {{ result.elapsedMs }} ms
              </span>
            </div>
            <p class="answer-text">{{ result.answer }}</p>
          </section>

          <!-- 空数据 -->
          <section v-if="result.rows.length === 0" class="empty-card">
            <el-icon :size="32"><DataLine /></el-icon>
            <p>暂无数据</p>
            <span>所选时间范围内没有符合指标的记录，换个时间范围或指标试试。</span>
          </section>

          <template v-else>
            <!-- 图表 -->
            <section v-show="hasChart" class="chart-card">
              <header class="card-head">
                <span>{{ result.metricName }}</span>
                <span class="card-sub">{{ chartTypeLabel(result.chart.type) }}</span>
              </header>
              <div ref="chartRef" class="chart-body"></div>
            </section>

            <!-- 表格 -->
            <section class="table-card">
              <header class="card-head">
                <span>数据明细</span>
                <span class="card-sub">{{ result.rows.length }} 行</span>
              </header>
              <el-table :data="result.rows" size="small" max-height="360" style="width: 100%" stripe>
                <el-table-column
                  v-for="col in result.columns"
                  :key="col.key"
                  :prop="col.key"
                  :label="col.label"
                  min-width="120"
                  show-overflow-tooltip
                >
                  <template #default="{ row }">
                    <span :class="{ 'num-cell': isNumeric(row[col.key]) }">{{ formatCell(row[col.key]) }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </template>
        </template>

        <!-- 首次空态 -->
        <section v-else-if="!asking && !error" class="idle-card">
          <div class="idle-icon">
            <el-icon :size="36"><DataLine /></el-icon>
          </div>
          <p class="idle-title">问一句，把数据变成结论</p>
          <p class="idle-desc">支持 9 个白名单指标：提问量、活跃用户、Token 用量、模型成本、响应时延、满意度、知识库排行、热门问题、每日成本。</p>
        </section>

        <div v-if="asking && !result" class="asking-card" v-loading="true" element-loading-text="正在解析问题并查询指标…"></div>
      </main>

      <!-- 指标清单 -->
      <aside class="metrics-panel">
        <div class="metrics-head">
          <h3>支持的指标</h3>
          <span>{{ metrics.length }} 个</span>
        </div>
        <button
          v-for="metric in metrics"
          :key="metric.key"
          class="metric-item"
          :disabled="asking"
          @click="fillExample(metric)"
        >
          <div class="metric-top">
            <span class="metric-name">{{ metric.name }}</span>
            <span class="metric-chart">{{ chartTypeLabel(metric.chartType) }}</span>
          </div>
          <p class="metric-desc">{{ metric.description }}</p>
          <p class="metric-example">“{{ metric.example }}”</p>
        </button>
        <div v-if="metrics.length === 0" class="metrics-empty">指标清单加载中…</div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DataLine, Search, WarningFilled } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { dataQaApi, type DataQaAskResult, type DataQaMetric } from '@/api/dataQa'

const metrics = ref<DataQaMetric[]>([])
const question = ref('')
const daysOverride = ref(0)
const asking = ref(false)
const error = ref('')
const result = ref<DataQaAskResult | null>(null)

const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

const hasChart = computed(() =>
  !!result.value && result.value.rows.length > 0 && result.value.chart.type !== 'table'
)

function chartTypeLabel(type?: string): string {
  const map: Record<string, string> = { line: '折线图', bar: '柱状图', pie: '饼图', table: '仅表格' }
  return map[type || ''] || '图表'
}

function isNumeric(value: unknown): boolean {
  return typeof value === 'number'
}

function formatCell(value: unknown): string {
  if (value == null || value === '') return '—'
  if (typeof value === 'number') {
    if (!Number.isInteger(value)) return String(Number(value.toFixed(4)))
    return value.toLocaleString('zh-CN')
  }
  return String(value)
}

function fillExample(metric: DataQaMetric) {
  question.value = metric.example
  daysOverride.value = 0
}

async function loadMetrics() {
  try {
    metrics.value = await dataQaApi.metrics()
  } catch (e: any) {
    ElMessage.error('加载指标清单失败：' + (e?.message || ''))
  }
}

async function ask() {
  const q = question.value.trim()
  if (!q || asking.value) return
  asking.value = true
  error.value = ''
  try {
    const res = await dataQaApi.ask({
      question: q,
      days: daysOverride.value > 0 ? daysOverride.value : null
    })
    result.value = res
    await nextTick()
    renderChart()
  } catch (e: any) {
    result.value = null
    chart?.clear()
    error.value = e?.message || '查询失败，请稍后重试'
  } finally {
    asking.value = false
  }
}

function renderChart() {
  const data = result.value
  if (!data) return
  if (data.chart.type === 'table' || data.rows.length === 0) {
    chart?.clear()
    return
  }
  chart = ensureChart()
  if (!chart) return
  chart.clear()

  const xKey = data.chart.xKey
  const yKey = data.chart.yKey
  const seriesName = data.chart.seriesName || data.metricName
  const xData = data.rows.map(row => String(row[xKey] ?? ''))
  const yData = data.rows.map(row => Number(row[yKey] ?? 0))
  const color = ['#3D5AFE', '#0EA5E9', '#7C3AED', '#10B981', '#F59E0B', '#F43F5E']

  if (data.chart.type === 'pie') {
    chart.setOption({
      color,
      tooltip: { trigger: 'item', formatter: '{b}<br/>{c}（{d}%）' },
      legend: { bottom: 0, itemWidth: 9, itemHeight: 9, textStyle: { fontSize: 11, color: '#6B7488' } },
      series: [
        {
          name: seriesName,
          type: 'pie',
          radius: ['42%', '66%'],
          center: ['50%', '44%'],
          itemStyle: { borderColor: '#fff', borderWidth: 2 },
          label: { fontSize: 11, color: '#4B5670', formatter: '{b}\n{d}%' },
          data: data.rows.map((row, i) => ({
            name: String(row[xKey] ?? `#${i + 1}`),
            value: Number(row[yKey] ?? 0)
          }))
        }
      ]
    })
    return
  }

  const isLine = data.chart.type === 'line'
  chart.setOption({
    color,
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 28, top: 30, bottom: 44 },
    xAxis: {
      type: 'category',
      data: xData,
      axisLine: { lineStyle: { color: '#E6EAF2' } },
      axisTick: { show: false },
      axisLabel: {
        fontSize: 10.5,
        color: '#8590A6',
        hideOverlap: true,
        rotate: xData.length > 10 ? 32 : 0
      }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#F1F4FA' } },
      axisLabel: { fontSize: 10.5, color: '#8590A6' }
    },
    series: [
      isLine
        ? {
            name: seriesName,
            type: 'line',
            smooth: true,
            symbolSize: 5,
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(61, 90, 254, 0.22)' },
                { offset: 1, color: 'rgba(61, 90, 254, 0.02)' }
              ])
            },
            data: yData
          }
        : {
            name: seriesName,
            type: 'bar',
            barMaxWidth: 30,
            itemStyle: { borderRadius: [5, 5, 0, 0] },
            data: yData
          }
    ]
  })
}

function ensureChart(): echarts.ECharts | null {
  if (!chartRef.value) return null
  return chart ?? echarts.init(chartRef.value)
}

function handleResize() {
  chart?.resize()
}

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  await loadMetrics()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.dataqa-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 30px 44px;
  background: var(--bg-page);
}

.page-header { margin-bottom: 16px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 7px; }
.page-title { font-family: 'Manrope', 'Noto Sans SC', sans-serif; font-size: 23px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #e0f2fe, #bae6fd); color: #0369a1;
}
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 860px; line-height: 1.65; }

.qa-layout { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 18px; align-items: start; }
.qa-main { display: flex; flex-direction: column; gap: 14px; min-width: 0; }

.ask-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 16px 18px;
  box-shadow: var(--shadow-card);
}
.ask-row { display: flex; gap: 10px; align-items: center; }
.ask-input { flex: 1; min-width: 0; }
.ask-range { width: 150px; flex-shrink: 0; }
.example-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.example-label { font-size: 11.5px; color: var(--ink-4); }
.example-chip {
  font-size: 11.5px; color: var(--ink-2);
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 11px;
  cursor: pointer;
  transition: all 160ms ease;
}
.example-chip:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); background: var(--brand-soft); }
.example-chip:disabled { opacity: 0.5; cursor: not-allowed; }

.error-card {
  display: flex; align-items: flex-start; gap: 10px;
  background: var(--danger-soft);
  border: 1px solid #FECACA;
  color: var(--danger-ink);
  border-radius: 12px;
  padding: 14px 16px;
}
.error-title { font-size: 13px; font-weight: 600; }
.error-hint { font-size: 11.5px; margin-top: 3px; opacity: 0.85; }

.answer-card {
  background: linear-gradient(135deg, #1B2BB8 0%, #3D5AFE 70%, #5570FF 100%);
  color: #fff;
  border-radius: 14px;
  padding: 18px 20px;
  box-shadow: var(--shadow-brand);
}
.answer-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.metric-tag {
  font-size: 11px; font-weight: 700;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 999px;
  padding: 3px 10px;
}
.answer-meta { font-size: 11px; opacity: 0.78; }
.answer-text { font-size: 15.5px; font-weight: 600; line-height: 1.72; margin-top: 10px; }

.chart-card, .table-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 14px 16px 10px;
  box-shadow: var(--shadow-card);
}
.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: 6px; }
.card-head > span:first-child { font-size: 13px; font-weight: 700; color: var(--ink-1); }
.card-sub { font-size: 10.5px; color: var(--ink-4); }
.chart-body { height: 320px; width: 100%; }
.table-card { padding-bottom: 14px; }
.num-cell { font-family: 'JetBrains Mono', monospace; font-size: 12px; color: var(--ink-2); }

.empty-card, .idle-card, .asking-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: var(--shadow-card);
  padding: 38px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--ink-4);
  text-align: center;
}
.empty-card p { font-size: 14px; font-weight: 600; color: var(--ink-2); }
.empty-card span { font-size: 12px; }
.asking-card { min-height: 200px; justify-content: center; }
.idle-icon {
  width: 68px; height: 68px; border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand);
  display: flex; align-items: center; justify-content: center;
}
.idle-title { font-size: 15px; font-weight: 700; color: var(--ink-1); }
.idle-desc { font-size: 12px; color: var(--ink-3); max-width: 520px; line-height: 1.7; }

.metrics-panel {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 14px;
  box-shadow: var(--shadow-card);
  position: sticky;
  top: 0;
  max-height: calc(100vh - 150px);
  overflow-y: auto;
}
.metrics-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.metrics-head h3 { font-size: 13px; font-weight: 700; color: var(--ink-1); }
.metrics-head span { font-size: 10.5px; color: var(--ink-4); }
.metric-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 10px 11px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: var(--bg-page);
  margin-bottom: 6px;
  cursor: pointer;
  transition: all 160ms ease;
}
.metric-item:hover:not(:disabled) { border-color: var(--brand); background: var(--brand-soft); }
.metric-item:disabled { opacity: 0.55; cursor: not-allowed; }
.metric-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.metric-name { font-size: 12.5px; font-weight: 600; color: var(--ink-1); }
.metric-chart { font-size: 9.5px; color: var(--ink-4); background: var(--bg-hover); border-radius: 4px; padding: 1px 6px; flex-shrink: 0; }
.metric-desc { font-size: 11px; color: var(--ink-3); line-height: 1.55; margin-top: 3px; }
.metric-example { font-size: 10.5px; color: var(--brand); margin-top: 4px; }
.metrics-empty { font-size: 12px; color: var(--ink-4); text-align: center; padding: 16px 0; }

@media (max-width: 1100px) {
  .qa-layout { grid-template-columns: minmax(0, 1fr); }
  .metrics-panel { position: static; max-height: none; }
}
@media (max-width: 720px) {
  .dataqa-page { padding: 18px 16px 34px; }
  .ask-row { flex-wrap: wrap; }
  .ask-range { width: 100%; }
  .chart-body { height: 260px; }
}
</style>
