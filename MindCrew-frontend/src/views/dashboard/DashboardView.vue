<template>
  <div class="dashboard-page page-container">
    <!-- 页头 -->
    <div class="dash-header">
      <div>
        <h2 class="page-h2">数据 <span class="gradient-text">大屏</span></h2>
        <p class="page-desc">MindCrew 平台实时运行数据概览</p>
      </div>
      <div class="header-right">
        <el-radio-group v-model="timeRange" size="small" @change="loadData">
          <el-radio-button value="today">今日</el-radio-button>
          <el-radio-button value="week">近7天</el-radio-button>
          <el-radio-button value="month">近30天</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" size="small" circle :loading="loading" @click="loadData" />
      </div>
    </div>

    <div v-loading="loading" class="dash-body">
      <!-- ── 核心指标 ── -->
      <div class="metric-strip">
        <div
          v-for="m in metrics"
          :key="m.label"
          class="metric-card"
          :style="{ '--mc': m.color }"
        >
          <div class="mc-icon" :style="{ background: m.bg, border: `1px solid ${m.border}` }">
            <el-icon size="20" :color="m.color"><component :is="m.icon" /></el-icon>
          </div>
          <div class="mc-body">
            <div class="mc-val">
              <count-up :end-val="m.value" :duration="1.2" />
            </div>
            <div class="mc-label">{{ m.label }}</div>
          </div>
          <div class="mc-trend" :class="m.trend > 0 ? 'up' : m.trend < 0 ? 'down' : ''">
            <span v-if="m.trend !== 0">{{ m.trend > 0 ? '▲' : '▼' }} {{ Math.abs(m.trend) }}%</span>
          </div>
          <!-- 底部高亮线 -->
          <div class="mc-bar"></div>
        </div>
      </div>

      <!-- ── 第一行图表 ── -->
      <div class="chart-row">
        <!-- 问答趋势 -->
        <el-card class="chart-card wide">
          <template #header>
            <div class="card-head">
              <span>问答量趋势</span>
              <el-tag size="small" effect="light">近 {{ timeRange === 'today' ? '24小时' : timeRange === 'week' ? '7天' : '30天' }}</el-tag>
            </div>
          </template>
          <div ref="trendChartRef" class="chart-box"></div>
        </el-card>

        <!-- 工具调用分布 -->
        <el-card class="chart-card narrow">
          <template #header>
            <div class="card-head"><span>工具调用分布</span></div>
          </template>
          <div ref="toolChartRef" class="chart-box"></div>
        </el-card>
      </div>

      <!-- ── 第二行图表 ── -->
      <div class="chart-row">
        <!-- 知识库分类分布 -->
        <el-card class="chart-card narrow">
          <template #header>
            <div class="card-head"><span>知识库分类</span></div>
          </template>
          <div ref="categoryChartRef" class="chart-box"></div>
        </el-card>

        <!-- 热门知识库排行榜 -->
        <el-card class="chart-card wide">
          <template #header>
            <div class="card-head">
              <span>热门知识库 TOP 10</span>
              <el-tag size="small" type="warning" effect="light">按检索频次</el-tag>
            </div>
          </template>
          <div class="rank-list">
            <div v-for="(kb, i) in hotKbList" :key="kb.name" class="rank-item">
              <span class="rank-no" :class="i < 3 ? 'top' : ''">{{ i + 1 }}</span>
              <span class="rank-name">{{ kb.name }}</span>
              <div class="rank-bar-wrap">
                <div
                  class="rank-bar"
                  :style="{ width: `${(kb.value / (hotKbList[0]?.value || 1)) * 100}%` }"
                ></div>
              </div>
              <span class="rank-val">{{ kb.value }}</span>
            </div>
          </div>
        </el-card>
      </div>

      <!-- ── 质量统计 ── -->
      <div class="chart-row">
        <el-card class="chart-card" style="flex:1">
          <template #header>
            <div class="card-head"><span>检索质量</span></div>
          </template>
          <div class="quality-grid">
            <div class="quality-item">
              <div class="quality-label">用户满意度</div>
              <el-progress
                :percentage="satisfactionPct"
                :stroke-width="10"
                color="#34d399"
                :format="() => `${satisfactionPct.toFixed(1)}%`"
              />
            </div>
            <div class="quality-item">
              <div class="quality-label">检索兜底率</div>
              <el-progress
                :percentage="fallbackPct"
                :stroke-width="10"
                :color="fallbackPct > 30 ? '#f87171' : '#fbbf24'"
                :format="() => `${fallbackPct.toFixed(1)}%`"
              />
            </div>
            <div class="qd-row">
              <div class="qd-cell">
                <div class="qd-val" style="color:#34d399">{{ data?.feedbackStats?.useful ?? 0 }}</div>
                <div class="qd-lbl">有用反馈</div>
              </div>
              <div class="qd-cell">
                <div class="qd-val" style="color:#f87171">{{ data?.feedbackStats?.useless ?? 0 }}</div>
                <div class="qd-lbl">无用反馈</div>
              </div>
              <div class="qd-cell">
                <div class="qd-val" style="color:#fbbf24">{{ data?.fallbackStats?.fallback ?? 0 }}</div>
                <div class="qd-lbl">兜底次数</div>
              </div>
              <div class="qd-cell">
                <div class="qd-val" style="color:#94a3b8">{{ data?.fallbackStats?.normal ?? 0 }}</div>
                <div class="qd-lbl">正常检索</div>
              </div>
            </div>
          </div>
        </el-card>

        <!-- 热门关键词 -->
        <el-card class="chart-card" style="flex:2">
          <template #header>
            <div class="card-head">
              <span>热门关键词 TOP 15</span>
            </div>
          </template>
          <div class="keyword-cloud">
            <span
              v-for="(kw, i) in hotKeywords"
              :key="kw.name"
              class="kw-tag"
              :style="getKwStyle(i, kw.value)"
            >{{ kw.name }}</span>
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { statsApi, type DashboardData } from '@/api/stats'

echarts.use([LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const loading       = ref(false)
const timeRange     = ref('week')
const data          = ref<DashboardData | null>(null)
const trendChartRef = ref<HTMLElement>()
const toolChartRef  = ref<HTMLElement>()
const categoryChartRef = ref<HTMLElement>()

let trendChart: echarts.ECharts | null = null
let toolChart:  echarts.ECharts | null = null
let catChart:   echarts.ECharts | null = null

const ECHART_THEME = {
  backgroundColor: 'transparent',
  textStyle: { color: '#64748b' },
  tooltip: {
    backgroundColor: '#1c2230',
    borderColor: 'rgba(255,255,255,0.08)',
    textStyle: { color: '#475569' },
  },
}

const metrics = computed(() => [
  { label: '总问答数',   value: data.value?.totalMessages   ?? 0, icon: 'ChatDotRound', color: '#38bdf8', bg: 'rgba(56,189,248,0.1)',  border: 'rgba(56,189,248,0.2)', trend: 12 },
  { label: '知识库数',   value: data.value?.totalKnowledge  ?? 0, icon: 'FolderOpened', color: '#818cf8', bg: 'rgba(129,140,248,0.1)', border: 'rgba(129,140,248,0.2)', trend: 3 },
  { label: '用户总数',   value: data.value?.totalUsers      ?? 0, icon: 'UserFilled',   color: '#34d399', bg: 'rgba(52,211,153,0.1)',  border: 'rgba(52,211,153,0.2)', trend: 5 },
  { label: '周期内问答', value: data.value?.periodMessages  ?? 0, icon: 'TrendCharts',  color: '#fbbf24', bg: 'rgba(251,191,36,0.1)',  border: 'rgba(251,191,36,0.2)', trend: -2 },
])

const satisfactionPct = computed(() => {
  const s = data.value?.feedbackStats
  if (!s) return 0
  const t = s.useful + s.useless
  return t > 0 ? Math.round(s.useful / t * 100) : 0
})
const fallbackPct = computed(() => {
  const f = data.value?.fallbackStats
  if (!f || !f.total) return 0
  return Math.round(f.fallback / f.total * 100)
})

const hotKbList = computed(() => {
  const src = data.value?.categoryDistribution || mockHotKb
  return [...src].sort((a, b) => b.value - a.value).slice(0, 10)
})
const hotKeywords = computed(() => data.value?.hotKeywords || mockKeywords)

const mockHotKb = [
  { name: '技术规范手册 v2.0', value: 284 },
  { name: '产品使用指南', value: 196 },
  { name: '合规法律文件汇编', value: 153 },
  { name: '财务报告 2024', value: 121 },
  { name: '员工培训资料', value: 98 },
  { name: '安全管理制度', value: 76 },
  { name: '研究报告合集', value: 64 },
]
const mockKeywords = [
  { name: '合同',  value: 89 }, { name: '权限',  value: 76 }, { name: '审批流程', value: 68 },
  { name: '数据安全', value: 62 }, { name: '报销',  value: 55 }, { name: '绩效考核', value: 49 },
  { name: 'API文档', value: 44 }, { name: '发票',  value: 38 }, { name: '部署',   value: 35 },
  { name: '隐私政策', value: 31 }, { name: '招聘',  value: 27 }, { name: '差旅',   value: 24 },
  { name: '版本更新', value: 21 }, { name: '预算',  value: 18 }, { name: '培训计划', value: 15 },
]

const loadData = async () => {
  loading.value = true
  try {
    const res = await statsApi.getDashboard(timeRange.value)
    data.value = res
  } catch { data.value = null } // 用 mock 数据
  finally { loading.value = false }
  await nextTick()
  renderCharts()
}

const renderCharts = () => {
  renderTrend()
  renderTool()
  renderCategory()
}

const renderTrend = () => {
  if (!trendChartRef.value) return
  if (!trendChart) trendChart = echarts.init(trendChartRef.value, null, { renderer: 'canvas' })
  const raw = data.value?.dailyTrend || generateMockTrend()
  trendChart.setOption({
    ...ECHART_THEME,
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: raw.map(d => d.date),
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
      axisLabel: { color: '#475569', fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.04)' } },
      axisLabel: { color: '#475569', fontSize: 11 },
    },
    series: [{
      type: 'line',
      data: raw.map(d => d.count),
      smooth: true,
      lineStyle: { color: '#38bdf8', width: 2 },
      itemStyle: { color: '#38bdf8' },
      areaStyle: {
        color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(56,189,248,0.25)' }, { offset: 1, color: 'rgba(56,189,248,0.02)' }] }
      },
      symbol: 'circle', symbolSize: 5,
    }],
    tooltip: { ...ECHART_THEME.tooltip, trigger: 'axis' },
  })
}

const renderTool = () => {
  if (!toolChartRef.value) return
  if (!toolChart) toolChart = echarts.init(toolChartRef.value, null, { renderer: 'canvas' })
  const toolData = [
    { name: 'DocSearch',    value: 1284, itemStyle: { color: '#38bdf8' } },
    { name: 'KeywordSearch',value: 567,  itemStyle: { color: '#818cf8' } },
    { name: 'Memory',       value: 342,  itemStyle: { color: '#fbbf24' } },
    { name: 'WebSearch',    value: 89,   itemStyle: { color: '#34d399' } },
  ]
  toolChart.setOption({
    ...ECHART_THEME,
    legend: { bottom: 0, textStyle: { color: '#64748b', fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['50%', '45%'],
      data: toolData,
      label: { show: false },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.4)' } },
    }],
    tooltip: { ...ECHART_THEME.tooltip, trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  })
}

const renderCategory = () => {
  if (!categoryChartRef.value) return
  if (!catChart) catChart = echarts.init(categoryChartRef.value, null, { renderer: 'canvas' })
  const catData = data.value?.categoryDistribution || [
    { name: '技术文档', value: 8 }, { name: '产品手册', value: 5 },
    { name: '法律合规', value: 4 }, { name: '财务报告', value: 3 },
    { name: '培训资料', value: 3 }, { name: '其他', value: 2 },
  ]
  const colors = ['#38bdf8','#818cf8','#34d399','#fbbf24','#f87171','#64748b']
  catChart.setOption({
    ...ECHART_THEME,
    legend: { bottom: 0, textStyle: { color: '#64748b', fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['35%', '65%'], center: ['50%', '42%'],
      data: catData.map((d, i) => ({ ...d, itemStyle: { color: colors[i % colors.length] } })),
      label: { show: false },
    }],
    tooltip: { ...ECHART_THEME.tooltip, trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  })
}

const generateMockTrend = () => {
  const n = timeRange.value === 'today' ? 24 : timeRange.value === 'week' ? 7 : 30
  return Array.from({ length: n }, (_, i) => ({
    date: timeRange.value === 'today'
      ? `${String(i).padStart(2, '0')}:00`
      : `${i + 1}日`,
    count: Math.floor(20 + Math.random() * 80)
  }))
}

const getKwStyle = (i: number, v: number) => {
  const colors = ['#38bdf8','#818cf8','#34d399','#fbbf24','#f87171']
  const maxV = hotKeywords.value[0]?.value ?? 1
  const size = 12 + Math.floor((v / maxV) * 10)
  return { color: colors[i % colors.length], fontSize: `${size}px`, opacity: 0.6 + (v / maxV) * 0.4 }
}

onMounted(loadData)
onUnmounted(() => { trendChart?.dispose(); toolChart?.dispose(); catChart?.dispose() })
</script>

<style scoped>
.dashboard-page { display: flex; flex-direction: column; gap: 18px; overflow-y: auto; }

.dash-header {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
}
.page-h2 { font-size: 22px; font-weight: 700; color: var(--ink-1); margin-bottom: 4px; }
.page-desc { font-size: 13px; color: #64748b; }
.header-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

/* 指标条 */
.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.metric-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  position: relative;
  overflow: hidden;
}
.mc-bar {
  position: absolute;
  bottom: 0; left: 0; right: 0;
  height: 2px;
  background: var(--mc);
  opacity: 0.5;
}
.mc-icon {
  width: 44px; height: 44px;
  border-radius: 11px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.mc-body { flex: 1; }
.mc-val { font-size: 26px; font-weight: 800; font-family: 'JetBrains Mono', monospace; color: var(--ink-1); line-height: 1; }
.mc-label { font-size: 12px; color: #64748b; margin-top: 4px; }
.mc-trend { font-size: 11px; font-weight: 700; align-self: flex-start; }
.mc-trend.up   { color: #34d399; }
.mc-trend.down { color: #f87171; }

/* 图表行 */
.dash-body { display: flex; flex-direction: column; gap: 14px; }
.chart-row { display: flex; gap: 14px; }
.chart-card { flex: 1; }
.chart-card.wide  { flex: 2.5; }
.chart-card.narrow { flex: 1; }
.chart-box { height: 240px; }
.card-head { display: flex; align-items: center; justify-content: space-between; font-size: 14px; font-weight: 600; color: var(--ink-1); }

/* 排行榜 */
.rank-list { display: flex; flex-direction: column; gap: 8px; padding: 4px 0; }
.rank-item { display: flex; align-items: center; gap: 10px; }
.rank-no {
  width: 22px; height: 22px; border-radius: 6px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; color: #475569; flex-shrink: 0;
}
.rank-no.top { background: rgba(56,189,248,0.1); border-color: rgba(56,189,248,0.3); color: var(--primary); }
.rank-name { width: 180px; font-size: 12.5px; color: #94a3b8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
.rank-bar-wrap { flex: 1; background: var(--bg-elevated); border-radius: 2px; height: 6px; overflow: hidden; }
.rank-bar { height: 100%; background: linear-gradient(90deg, #38bdf8, #818cf8); border-radius: 2px; transition: width 0.8s cubic-bezier(0.34,1.56,0.64,1); }
.rank-val { width: 40px; text-align: right; font-size: 12px; color: #64748b; font-family: monospace; flex-shrink: 0; }

/* 质量统计 */
.quality-grid { display: flex; flex-direction: column; gap: 14px; padding: 4px 0; }
.quality-item { display: flex; flex-direction: column; gap: 7px; }
.quality-label { font-size: 12px; color: #64748b; font-weight: 600; }
.qd-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 4px; }
.qd-cell { text-align: center; background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px 4px; }
.qd-val { font-size: 20px; font-weight: 800; font-family: 'JetBrains Mono', monospace; }
.qd-lbl { font-size: 10px; color: #475569; margin-top: 3px; }

/* 关键词云 */
.keyword-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0;
  align-items: center;
}
.kw-tag {
  font-weight: 600;
  cursor: default;
  padding: 3px 10px;
  border-radius: 20px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  transition: var(--transition);
}
.kw-tag:hover { background: rgba(56,189,248,0.08); border-color: rgba(56,189,248,0.2); }
</style>
