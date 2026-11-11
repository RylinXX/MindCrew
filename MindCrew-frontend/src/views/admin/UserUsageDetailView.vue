<template>
  <div class="usage-detail">
    <header class="header">
      <button class="back-btn" @click="$router.back()">
        <el-icon size="14"><ArrowLeft /></el-icon> 返回
      </button>
      <h2 class="title">用户使用详情</h2>
      <span class="title-tag">任务 13.6 · 管理员逐用户钻取</span>
    </header>

    <!-- 时间筛选 -->
    <div class="filter-bar">
      <el-radio-group v-model="rangeKey" size="small" @change="onRangeChange">
        <el-radio-button label="today">今日</el-radio-button>
        <el-radio-button label="week">本周</el-radio-button>
        <el-radio-button label="month">本月</el-radio-button>
        <el-radio-button label="quarter">近 90 天</el-radio-button>
      </el-radio-group>
      <el-date-picker
        v-model="customRange"
        type="daterange"
        size="small"
        value-format="YYYY-MM-DD"
        start-placeholder="自定义起"
        end-placeholder="自定义止"
        @change="onCustomRangeChange"
      />
    </div>

    <!-- 摘要 · 主指标 (2 大卡) -->
    <div class="hero-grid" v-loading="loading">
      <article class="hero-card primary">
        <div class="hero-label">
          <el-icon size="14"><ChatLineSquare /></el-icon>
          <span>本期对话数</span>
        </div>
        <div class="hero-value">{{ formatNumber(summary?.chatCount ?? 0) }}</div>
        <div class="hero-sub">含 Golden Pair 命中 {{ summary?.goldenHitCount ?? 0 }} 次</div>
      </article>

      <article class="hero-card cost">
        <div class="hero-label">
          <el-icon size="14"><Money /></el-icon>
          <span>总成本</span>
        </div>
        <div class="hero-value">{{ formatMoney(summary?.costCny) }}</div>
        <div class="hero-sub">
          按模型实际定价计算 · <a href="javascript:void(0)" @click="showCostBreakdown">查看明细</a>
        </div>
      </article>
    </div>

    <!-- 摘要 · 次要指标（6 小卡，更克制的视觉） -->
    <div class="mini-grid">
      <div class="mini-card">
        <div class="mini-label">Input</div>
        <div class="mini-value">{{ formatNumber(summary?.inputTokens ?? 0) }}</div>
        <div class="mini-unit">tokens</div>
      </div>
      <div class="mini-card">
        <div class="mini-label">Output</div>
        <div class="mini-value">{{ formatNumber(summary?.outputTokens ?? 0) }}</div>
        <div class="mini-unit">tokens</div>
      </div>
      <div class="mini-card">
        <div class="mini-label">Embedding</div>
        <div class="mini-value">{{ formatNumber(summary?.embeddingTokens ?? 0) }}</div>
        <div class="mini-unit">tokens</div>
      </div>
      <div class="mini-card">
        <div class="mini-label">视觉识别</div>
        <div class="mini-value">{{ formatNumber(summary?.visionCalls ?? 0) }}</div>
        <div class="mini-unit">次</div>
      </div>
      <div class="mini-card">
        <div class="mini-label">语音识别</div>
        <div class="mini-value">{{ formatNumber(summary?.asrSeconds ?? 0) }}</div>
        <div class="mini-unit">秒</div>
      </div>
      <div class="mini-card">
        <div class="mini-label">已审核命中</div>
        <div class="mini-value">{{ formatNumber(summary?.goldenHitCount ?? 0) }}</div>
        <div class="mini-unit">次</div>
      </div>
    </div>

    <!-- 每日明细表格 -->
    <section class="daily-table">
      <div class="section-header">
        <h3>每日明细（最近 {{ summary?.dayList?.length ?? 0 }} 天）</h3>
      </div>
      <el-table :data="summary?.dayList || []" stripe size="small">
        <el-table-column prop="statDate" label="日期" width="120" />
        <el-table-column prop="chatCount" label="对话数" width="100" align="right" />
        <el-table-column prop="inputTokens" label="Input" width="120" align="right">
          <template #default="{ row }">{{ formatNumber(row.inputTokens) }}</template>
        </el-table-column>
        <el-table-column prop="outputTokens" label="Output" width="120" align="right">
          <template #default="{ row }">{{ formatNumber(row.outputTokens) }}</template>
        </el-table-column>
        <el-table-column prop="visionCalls" label="VL 次" width="90" align="right" />
        <el-table-column prop="asrSeconds" label="ASR 秒" width="100" align="right" />
        <el-table-column prop="goldenHitCount" label="Golden 命中" width="120" align="right" />
        <el-table-column prop="costCny" label="成本 ¥" align="right">
          <template #default="{ row }">
            <span class="cost-cell">{{ formatMoney(row.costCny) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!summary?.dayList?.length && !loading" class="empty">该用户在所选区间内无用量记录</div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ChatLineSquare, Money } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { usageApi, type UsageSummary } from '@/api/usage'

const route = useRoute()
const userId = ref<number>(Number(route.params.id))

const loading = ref(false)
const summary = ref<UsageSummary | null>(null)
const rangeKey = ref<'today' | 'week' | 'month' | 'quarter'>('month')
const customRange = ref<[string, string] | null>(null)

const rangeForKey = (k: string): [string, string] => {
  const today = new Date()
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  const subDays = (n: number) => {
    const d = new Date(today)
    d.setDate(d.getDate() - n)
    return d
  }
  if (k === 'today')   return [fmt(today), fmt(today)]
  if (k === 'week')    return [fmt(subDays(6)), fmt(today)]
  if (k === 'month')   return [fmt(new Date(today.getFullYear(), today.getMonth(), 1)), fmt(today)]
  return [fmt(subDays(89)), fmt(today)]
}

const load = async () => {
  loading.value = true
  try {
    const [from, to] = customRange.value ?? rangeForKey(rangeKey.value)
    const res: any = await usageApi.userSummary(userId.value, from, to)
    summary.value = res?.data ?? res
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally {
    loading.value = false
  }
}

onMounted(load)

const onRangeChange = () => {
  customRange.value = null
  load()
}
const onCustomRangeChange = () => {
  load()
}

const formatNumber = (n: number) =>
  n == null ? '0' : new Intl.NumberFormat('en-US').format(Number(n))

/** 成本动态精度：钱大用 2 位、钱小用 4-6 位 · 0 显示"未计费" */
const formatMoney = (n?: number) => {
  const v = Number(n ?? 0)
  if (v === 0) return '¥0'
  if (v >= 1)    return '¥' + v.toFixed(2)
  if (v >= 0.01) return '¥' + v.toFixed(4)
  if (v >= 0.0001) return '¥' + v.toFixed(6)
  return '¥' + v.toExponential(2)
}

const showCostBreakdown = () => {
  const s = summary.value
  if (!s) return
  const lines = (s.dayList || [])
    .filter(d => Number(d.costCny) > 0)
    .map(d => `${d.statDate}：¥${Number(d.costCny).toFixed(6)} · ${d.chatCount} 对话 · ${d.inputTokens + d.outputTokens} tokens`)
    .join('\n')
  ElMessageBox.alert(
    lines || '本期内尚未产生成本。\n\n说明：成本由模型实际定价决定。若所有定价为 0，请检查 model_pricing 表。',
    '成本明细',
    { confirmButtonText: '我知道了', customClass: 'cost-breakdown-box' }
  )
}
</script>

<style scoped>
.usage-detail { padding: 28px 32px 48px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.header { display: flex; align-items: center; gap: 14px; margin-bottom: 22px; }
.back-btn {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 6px 12px; background: var(--bg-surface);
  border: 1px solid var(--line); border-radius: 6px;
  font-size: 12.5px; color: var(--ink-2); cursor: pointer;
}
.back-btn:hover { background: var(--bg-hover); color: var(--ink-1); }
.title { font-size: 22px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #ddd6fe, #c4b5fd); color: #5b21b6;
}

.filter-bar { display: flex; gap: 14px; margin-bottom: 22px; align-items: center; flex-wrap: wrap; }

/* 主指标卡片 · 突出对话数 + 成本 */
.hero-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 14px;
}
.hero-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 22px 28px;
  position: relative;
  overflow: hidden;
}
.hero-card.primary {
  background: linear-gradient(135deg, var(--brand-soft) 0%, var(--bg-surface) 70%);
  border-color: var(--brand-soft-2);
}
.hero-card.cost {
  background: linear-gradient(135deg, rgba(245,158,11,0.10) 0%, var(--bg-surface) 70%);
  border-color: rgba(245,158,11,0.28);
}
.hero-label {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 600; color: var(--ink-2);
  text-transform: uppercase; letter-spacing: 0.05em;
}
.hero-card.primary .hero-label .el-icon { color: var(--brand); }
.hero-card.cost .hero-label .el-icon { color: #b45309; }
.hero-value {
  font-size: 36px;
  font-weight: 800;
  font-family: 'Manrope', sans-serif;
  color: var(--ink-1);
  letter-spacing: -0.025em;
  margin: 6px 0 4px;
  line-height: 1.05;
}
.hero-card.cost .hero-value { color: #b45309; }
.hero-sub { font-size: 12px; color: var(--ink-3); }
.hero-sub a { color: var(--brand); text-decoration: none; font-weight: 600; }
.hero-sub a:hover { text-decoration: underline; }

/* 次要指标卡片 · 一行 6 个紧凑卡 */
.mini-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 10px;
  margin-bottom: 28px;
}
.mini-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.mini-label { font-size: 11px; color: var(--ink-3); font-weight: 500; }
.mini-value {
  font-size: 18px;
  font-weight: 700;
  font-family: 'Manrope', sans-serif;
  color: var(--ink-1);
  letter-spacing: -0.01em;
  line-height: 1.15;
}
.mini-unit { font-size: 10.5px; color: var(--ink-4); font-weight: 500; }

@media (max-width: 1100px) {
  .mini-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 720px) {
  .hero-grid { grid-template-columns: 1fr; }
  .mini-grid { grid-template-columns: repeat(2, 1fr); }
}

.daily-table { background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px; padding: 16px 20px; }
.section-header { margin-bottom: 12px; }
.section-header h3 { font-size: 14px; font-weight: 700; color: var(--ink-1); }
.cost-cell { font-family: 'JetBrains Mono', monospace; color: #b45309; font-weight: 600; }
.empty { padding: 40px; text-align: center; color: var(--ink-3); font-size: 13px; }
</style>
