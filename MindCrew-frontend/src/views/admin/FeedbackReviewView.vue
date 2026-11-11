<template>
  <div class="fb-page">
    <header class="page-header">
      <div class="page-header-l">
        <div class="title-row">
          <h2 class="page-title">反馈审核</h2>
          <span class="title-tag">校正反哺闭环</span>
        </div>
        <p class="page-desc">
          用户对 AI 答复的点赞/踩 + 校正内容会沉淀到这里。审核员认可后会写入 Golden Pair 库，
          相似问题再问时 AI 直接返回标准答案，**实现"AI 越用越准"**。
        </p>
      </div>
      <div class="stat-strip">
        <div class="stat-card" v-for="s in statsList" :key="s.label">
          <div class="stat-num" :style="{ color: s.color }">{{ s.value }}</div>
          <div class="stat-lbl">{{ s.label }}</div>
        </div>
      </div>
    </header>

    <main class="fb-main" v-loading="loading">
      <div class="filter-bar">
        <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:140px" @change="loadList">
          <el-option label="待审核"  value="pending" />
          <el-option label="已收录"  value="approved" />
          <el-option label="已驳回"  value="rejected" />
        </el-select>
        <el-select v-model="filterRating" placeholder="全部评分" clearable style="width:140px" @change="loadList">
          <el-option label="👍 赞"  value="up" />
          <el-option label="👎 踩"  value="down" />
        </el-select>
        <el-button :icon="Refresh" @click="loadList" />
      </div>

      <div v-if="!loading && list.length === 0" class="empty">
        <div class="empty-ring"></div>
        <p>暂无反馈</p>
      </div>

      <article v-for="item in list" :key="item.id" class="fb-card" :class="`status-${item.status}`">
        <header class="fb-head">
          <span class="rating-tag" :class="item.rating">
            {{ item.rating === 'up' ? '👍 赞' : '👎 踩' }}
          </span>
          <span class="status-tag" :class="`s-${item.status}`">
            {{ statusLabel(item.status) }}
          </span>
          <span class="fb-time">{{ formatTime(item.createTime) }}</span>
        </header>

        <div class="fb-body">
          <div v-if="item.comment" class="block">
            <span class="block-label">用户评论</span>
            <div class="block-text">{{ item.comment }}</div>
          </div>
          <div v-if="item.correctionText" class="block correction">
            <span class="block-label">用户提供的正确答案</span>
            <div class="block-text correction-text">{{ item.correctionText }}</div>
          </div>
          <div v-if="item.reviewerNote" class="block">
            <span class="block-label">审核备注</span>
            <div class="block-text">{{ item.reviewerNote }}</div>
          </div>
        </div>

        <footer v-if="item.status === 'pending'" class="fb-actions">
          <el-button size="small" type="primary" :icon="CircleCheck" @click="openApprove(item)">
            认可并收录为 Golden Pair
          </el-button>
          <el-button size="small" type="danger" :icon="Close" plain @click="openReject(item)">
            驳回
          </el-button>
        </footer>
        <footer v-else-if="item.goldenPairId" class="fb-link">
          ✓ 已生成 Golden Pair #{{ item.goldenPairId }}
        </footer>
      </article>

      <div v-if="total > pageSize" class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          background
          layout="prev, pager, next"
          @current-change="loadList"
        />
      </div>
    </main>

    <!-- 收录对话框 -->
    <el-dialog v-model="approveVisible" title="收录为 Golden Pair" width="640px" :close-on-click-modal="false">
      <div class="approve-hint">
        审核员可在用户答案基础上微调；提交后写入 Golden Pair 库，相似问题命中后直接返回此答案。
      </div>
      <el-input
        v-model="finalAnswer"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 18 }"
        placeholder="标准答案（可空 · 默认用用户提供的纠正文本）"
        maxlength="5000"
        show-word-limit
      />
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmApprove">收录</el-button>
      </template>
    </el-dialog>

    <!-- 驳回对话框 -->
    <el-dialog v-model="rejectVisible" title="驳回反馈" width="480px">
      <el-input v-model="rejectNote" type="textarea" :rows="4" placeholder="驳回原因（可空）" maxlength="500" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="confirmReject">驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, CircleCheck, Close } from '@element-plus/icons-vue'
import { feedbackApi, type QaFeedback } from '@/api/feedback'
import { goldenPairApi } from '@/api/goldenPair'

const loading = ref(false)
const list = ref<QaFeedback[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const filterStatus = ref('pending')
const filterRating = ref('')

const stats = ref({ pending: 0, approved: 0, rejected: 0 })
const goldenStats = ref({ total: 0, totalHits: 0 })

const statsList = computed(() => [
  { label: '待审核', value: stats.value.pending,  color: '#f59e0b' },
  { label: '已收录', value: stats.value.approved, color: '#34d399' },
  { label: '已驳回', value: stats.value.rejected, color: '#94a3b8' },
  { label: 'Golden Pair', value: goldenStats.value.total, color: '#38bdf8' },
  { label: '命中累计', value: goldenStats.value.totalHits, color: '#a78bfa' },
])

async function loadList() {
  loading.value = true
  try {
    const res: any = await feedbackApi.page({
      current: currentPage.value,
      size: pageSize.value,
      status: filterStatus.value || undefined,
      rating: filterRating.value || undefined,
    })
    const data = res?.data ?? res
    list.value = data?.records || []
    total.value = data?.total || 0
  } finally { loading.value = false }
}

async function loadStats() {
  try {
    const a: any = await feedbackApi.count()
    stats.value = a?.data ?? a
  } catch {}
  try {
    const g: any = await goldenPairApi.stats()
    goldenStats.value = g?.data ?? g
  } catch {}
}

onMounted(() => { loadList(); loadStats() })

// ── 收录 ──
const approveVisible = ref(false)
const finalAnswer = ref('')
const submitting = ref(false)
const currentTarget = ref<QaFeedback | null>(null)

function openApprove(item: QaFeedback) {
  currentTarget.value = item
  finalAnswer.value = item.correctionText || ''
  approveVisible.value = true
}

async function confirmApprove() {
  if (!currentTarget.value) return
  submitting.value = true
  try {
    await goldenPairApi.fromFeedback(currentTarget.value.id, finalAnswer.value.trim() || undefined)
    ElMessage.success('已收录为 Golden Pair · AI 下次遇到相似问题会直接命中')
    approveVisible.value = false
    await Promise.all([loadList(), loadStats()])
  } catch (e: any) {
    ElMessage.error('收录失败：' + (e?.message || ''))
  } finally { submitting.value = false }
}

// ── 驳回 ──
const rejectVisible = ref(false)
const rejectNote = ref('')

function openReject(item: QaFeedback) {
  currentTarget.value = item
  rejectNote.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (!currentTarget.value) return
  submitting.value = true
  try {
    await feedbackApi.reject(currentTarget.value.id, rejectNote.value)
    ElMessage.success('已驳回')
    rejectVisible.value = false
    await Promise.all([loadList(), loadStats()])
  } catch (e: any) {
    ElMessage.error('驳回失败：' + (e?.message || ''))
  } finally { submitting.value = false }
}

function statusLabel(s: string) {
  return ({ pending: '待审核', approved: '已收录', rejected: '已驳回' } as any)[s] || s
}

function formatTime(t?: string) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.fb-page { padding: 28px 32px 48px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 28px; flex-wrap: wrap; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); letter-spacing: -0.02em; }
.title-tag { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #d1fae5, #a7f3d0); color: #047857; }
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }
.stat-strip { display: flex; gap: 10px; flex-wrap: wrap; }
.stat-card { padding: 10px 16px; background: var(--bg-surface); border: 1px solid var(--line);
  border-radius: 10px; min-width: 92px; text-align: center; }
.stat-num { font-size: 22px; font-weight: 700; font-family: 'Manrope', sans-serif; }
.stat-lbl { font-size: 11px; color: var(--ink-3); margin-top: 2px; }

.filter-bar { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; }

.fb-card { background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px;
  padding: 16px 20px; margin-bottom: 14px; transition: box-shadow 0.15s; }
.fb-card:hover { box-shadow: var(--shadow-md); }
.fb-card.status-pending { border-left: 4px solid #f59e0b; }
.fb-card.status-approved { border-left: 4px solid #34d399; }
.fb-card.status-rejected { border-left: 4px solid #94a3b8; opacity: 0.75; }

.fb-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.rating-tag { font-size: 11.5px; font-weight: 600; padding: 3px 10px; border-radius: 999px; }
.rating-tag.up { background: rgba(52,211,153,0.15); color: #047857; }
.rating-tag.down { background: rgba(248,113,113,0.15); color: #b91c1c; }
.status-tag { font-size: 10.5px; font-weight: 600; padding: 2px 8px; border-radius: 999px;
  background: var(--bg-subtle); color: var(--ink-3); }
.status-tag.s-pending { background: rgba(245,158,11,0.15); color: #b45309; }
.status-tag.s-approved { background: rgba(52,211,153,0.15); color: #047857; }
.fb-time { font-size: 11.5px; color: var(--ink-4); margin-left: auto; }

.fb-body { display: flex; flex-direction: column; gap: 10px; }
.block { display: flex; flex-direction: column; gap: 4px; }
.block-label { font-size: 10.5px; font-weight: 700; text-transform: uppercase; color: var(--ink-4); letter-spacing: 0.04em; }
.block-text { font-size: 13.5px; color: var(--ink-1); line-height: 1.6; }
.block.correction { background: rgba(56,189,248,0.05); border: 1px solid rgba(56,189,248,0.18); padding: 10px 12px; border-radius: 8px; }
.correction-text { white-space: pre-wrap; }

.fb-actions { display: flex; gap: 8px; margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--line); }
.fb-link { margin-top: 10px; font-size: 12px; color: var(--ink-3); }

.approve-hint { padding: 10px 14px; margin-bottom: 14px; background: rgba(52,211,153,0.08);
  border: 1px solid rgba(52,211,153,0.25); border-radius: 8px; font-size: 12.5px; color: var(--ink-2); line-height: 1.55; }

.empty { padding: 80px 0; text-align: center; color: var(--ink-3); }
.empty-ring { width: 48px; height: 48px; border: 3px solid var(--line); border-top-color: var(--brand);
  border-radius: 50%; margin: 0 auto 14px; }

.pagination { margin-top: 20px; text-align: center; }
</style>
