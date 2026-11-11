<template>
  <div class="coach-stats">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">教练学习统计</h2>
          <span class="title-tag">团队 · 谁学了什么</span>
        </div>
        <p class="page-desc">
          统计近 N 天所有员工的练习情况：练习次数、答题量、平均分、正确率。
          帮助主管识别"谁还没开始用"、"谁练得多但分数低"、"谁需要单独辅导"。
        </p>
      </div>
      <div class="filter-bar">
        <el-select v-model="recentDays" @change="load" style="width: 130px;">
          <el-option label="近 7 天"  :value="7" />
          <el-option label="近 30 天" :value="30" />
          <el-option label="近 90 天" :value="90" />
        </el-select>
        <el-button :icon="Refresh" @click="load" />
      </div>
    </header>

    <div class="stat-table" v-loading="loading">
      <el-table :data="rows" stripe style="width: 100%">
        <el-table-column prop="userId"       label="用户 ID"  width="120" />
        <el-table-column prop="sessions"     label="练习次数" width="120" sortable />
        <el-table-column prop="questionDone" label="答题量"   width="120" sortable />
        <el-table-column prop="avgScore"     label="平均分"   width="120" sortable>
          <template #default="{ row }">
            <span :class="scoreColor(row.avgScore)">{{ row.avgScore }}</span>
          </template>
        </el-table-column>
        <el-table-column label="正确率" sortable :sort-by="'accuracy'">
          <template #default="{ row }">
            <div class="acc-cell">
              <div class="acc-bar">
                <div class="acc-fill" :style="{ width: Math.round((row.accuracy || 0) * 100) + '%' }"></div>
              </div>
              <span>{{ Math.round((row.accuracy || 0) * 100) }}%</span>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && rows.length === 0" class="empty">
        <p>所选时间段内还没有练习记录</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { coachApi, type TeamStatRow } from '@/api/coach'

const recentDays = ref(30)
const rows = ref<TeamStatRow[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res: any = await coachApi.teamStats(recentDays.value)
    rows.value = res?.data ?? res ?? []
  } finally { loading.value = false }
}

function scoreColor(s: number) {
  if (s >= 85) return 'score-good'
  if (s >= 60) return 'score-mid'
  return 'score-bad'
}

onMounted(load)
</script>

<style scoped>
.coach-stats { padding: 28px 32px 48px; height: 100%; overflow-y: auto; background: var(--bg-page); }

.page-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  gap: 24px; margin-bottom: 24px; flex-wrap: wrap;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #ede9fe, #ddd6fe); color: #6d28d9;
}
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }

.filter-bar { display: flex; gap: 10px; align-items: center; }

.stat-table {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 8px 4px;
}

.acc-cell { display: flex; align-items: center; gap: 10px; }
.acc-bar { width: 120px; height: 6px; background: var(--bg-hover); border-radius: 3px; overflow: hidden; }
.acc-fill { height: 100%; background: linear-gradient(90deg, #ef4444, #f59e0b 60%, #34d399); }

.score-good { color: #047857; font-weight: 700; }
.score-mid  { color: #b45309; font-weight: 600; }
.score-bad  { color: #b91c1c; font-weight: 600; }

.empty { padding: 40px 0; text-align: center; color: var(--ink-3); }
</style>
