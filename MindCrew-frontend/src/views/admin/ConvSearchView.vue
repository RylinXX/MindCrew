<template>
  <div class="conv-search-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">历史对话搜索</h2>
          <span class="title-tag">任务 13.5 · 主管视角</span>
        </div>
        <p class="page-desc">
          {{ userStore.isAdmin
            ? '可查全员的 AI 对话历史，按关键词 / 用户 / 部门 / 时间 / 敏感标记筛选；对违规或敏感的对话打标记便于审计。'
            : '可查看你自己的历史对话。' }}
        </p>
      </div>
    </header>

    <!-- 筛选条 -->
    <div class="filter-bar">
      <el-input
        v-model="filters.keyword"
        placeholder="关键词（在用户问题/AI 答案中搜索）"
        clearable
        style="width: 280px"
        @keyup.enter="search(1)"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select
        v-if="userStore.isAdmin"
        v-model="filters.userId"
        placeholder="筛选用户"
        clearable
        filterable
        remote
        :remote-method="loadUserOptions"
        :loading="userLoading"
        style="width: 200px"
      >
        <el-option
          v-for="u in userOptions"
          :key="u.id"
          :label="u.nickname || u.username"
          :value="u.id"
        >
          <div class="user-opt">
            <span>{{ u.nickname || u.username }}</span>
            <span class="opt-sub">{{ u.role }}</span>
          </div>
        </el-option>
      </el-select>

      <el-select
        v-if="userStore.isAdmin"
        v-model="filters.deptId"
        placeholder="筛选部门（含子部门）"
        clearable
        filterable
        style="width: 200px"
      >
        <el-option
          v-for="d in deptOptions"
          :key="d.id"
          :label="d.name"
          :value="d.id"
        />
      </el-select>

      <el-date-picker
        v-model="dateRange"
        type="datetimerange"
        range-separator="→"
        start-placeholder="开始"
        end-placeholder="结束"
        value-format="YYYY-MM-DDTHH:mm:ss"
        style="width: 360px"
      />

      <el-checkbox v-model="filters.onlyFlagged" border>仅敏感</el-checkbox>

      <el-button type="primary" :icon="Search" @click="search(1)">搜索</el-button>
      <el-button :icon="RefreshLeft" @click="resetFilters">重置</el-button>
    </div>

    <!-- 结果列表 -->
    <div class="result-section">
      <div v-if="loading" class="loading-state">
        <el-icon class="is-loading"><Loading /></el-icon> 加载中...
      </div>

      <div v-else-if="!records.length" class="empty-state">
        <el-empty description="没有匹配的对话" />
      </div>

      <div v-else class="conv-list">
        <div
          v-for="r in records"
          :key="r.id"
          class="conv-card"
          :class="{ flagged: r.isFlagged === 1 }"
          @click="openDetail(r.id)"
        >
          <div class="conv-head">
            <div class="conv-title">{{ r.title || '(未命名会话)' }}</div>
            <div class="conv-meta">
              <el-tag v-if="r.isFlagged === 1" type="danger" size="small" effect="dark">⚠ 敏感</el-tag>
              <span class="meta-tag">{{ r.messageCount || 0 }} 条消息</span>
              <span class="meta-time">{{ formatTime(r.lastActive) }}</span>
            </div>
          </div>

          <div class="conv-user">
            <el-icon><User /></el-icon>
            <span class="user-name">{{ r.nickname || r.username || '#' + r.userId }}</span>
            <span v-if="r.departmentName" class="user-dept">· {{ r.departmentName }}</span>
            <span v-if="r.flaggedByName" class="flag-by">
              · 由 {{ r.flaggedByName }} 标记于 {{ formatTime(r.flaggedAt) }}
            </span>
          </div>

          <div v-if="r.flagNote" class="conv-flag-note">
            <el-icon><WarningFilled /></el-icon>
            {{ r.flagNote }}
          </div>

          <div v-if="r.matchedSnippets && r.matchedSnippets.length" class="conv-snippets">
            <div
              v-for="s in r.matchedSnippets"
              :key="s.messageId"
              class="snippet-row"
              :class="s.role"
            >
              <span class="snippet-role">{{ s.role === 'user' ? '问' : '答' }}</span>
              <span class="snippet-text" v-html="highlight(s.snippet)"></span>
            </div>
          </div>
        </div>
      </div>

      <div v-if="total > 0" class="pagination-bar">
        <el-pagination
          v-model:current-page="filters.current"
          v-model:page-size="filters.size"
          layout="prev, pager, next, jumper, total"
          :total="total"
          @current-change="search()"
        />
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer
      v-model="detailVisible"
      title="对话详情"
      direction="rtl"
      size="640px"
    >
      <div v-if="detailLoading" class="detail-loading">
        <el-icon class="is-loading"><Loading /></el-icon> 加载中...
      </div>
      <div v-else-if="detail" class="detail-content">
        <!-- 头部信息 -->
        <div class="detail-head">
          <div class="detail-title">{{ detail.conversation.title || '(未命名会话)' }}</div>
          <div class="detail-meta">
            <el-icon><User /></el-icon>
            {{ detail.nickname || detail.username }}
            <span v-if="detail.departmentName" class="dim">· {{ detail.departmentName }}</span>
          </div>
          <div class="detail-meta dim">
            <el-icon><Clock /></el-icon>
            {{ formatTime(detail.conversation.lastActive) }} · 共 {{ detail.messages.length }} 条
          </div>

          <!-- 敏感标记区 -->
          <div v-if="userStore.isAdmin" class="flag-section">
            <template v-if="detail.conversation.isFlagged === 1">
              <el-alert
                type="error"
                :closable="false"
                show-icon
              >
                <template #title>
                  ⚠ 已标记为敏感 · {{ detail.conversation.flagNote || '(无备注)' }}
                </template>
              </el-alert>
              <el-button
                size="small"
                type="warning"
                plain
                style="margin-top: 8px"
                @click="unflagConv"
              >取消敏感标记</el-button>
            </template>
            <template v-else>
              <el-input
                v-model="flagNote"
                placeholder="标记原因（如：涉密 / 越权 / 失实 / 投诉）"
                size="small"
                style="width: 70%; margin-right: 8px"
              />
              <el-button
                type="danger"
                size="small"
                :icon="WarningFilled"
                @click="flagConv"
              >标记敏感</el-button>
            </template>
          </div>
        </div>

        <!-- 消息列表 -->
        <div class="detail-messages">
          <div
            v-for="m in detail.messages"
            :key="m.id"
            class="msg-row"
            :class="m.role"
          >
            <div class="msg-role-tag">{{ m.role === 'user' ? '用户' : 'AI' }}</div>
            <div class="msg-body">
              <div class="msg-text">{{ m.content }}</div>
              <div class="msg-foot">
                <span class="msg-time">{{ formatTime(m.createTime) }}</span>
                <el-tag v-if="m.feedback === 1" type="success" size="small">👍 有用</el-tag>
                <el-tag v-if="m.feedback === -1" type="danger" size="small">👎 没用</el-tag>
                <el-button
                  v-if="parseRetrievalLog(m.retrievalLog)"
                  link
                  size="small"
                  @click="showRetrievalLog(m)"
                >📊 检索过程</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 检索过程弹窗（简化版） -->
    <el-dialog v-model="retrievalVisible" title="RAG 检索过程" width="600px">
      <pre v-if="currentRetrievalLog" class="rl-json">{{ JSON.stringify(currentRetrievalLog, null, 2) }}</pre>
      <div v-else class="dim">暂无检索日志</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, RefreshLeft, Loading, User, WarningFilled, Clock,
} from '@element-plus/icons-vue'
import { adminConvApi, type ConvMatch, type ConvDetail } from '@/api/conversationSearch'
import { userApi } from '@/api/user'
import { departmentApi } from '@/api/orgAcl'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

// ─── 筛选条件 ───
const filters = reactive({
  keyword: '',
  userId: undefined as number | undefined,
  deptId: undefined as number | undefined,
  onlyFlagged: false,
  current: 1,
  size: 20,
})
const dateRange = ref<string[] | null>(null)

// ─── 列表数据 ───
const records = ref<ConvMatch[]>([])
const total = ref(0)
const loading = ref(false)

// ─── 用户/部门选项 ───
const userOptions = ref<any[]>([])
const userLoading = ref(false)
const deptOptions = ref<any[]>([])

async function loadUserOptions(query: string) {
  if (!userStore.isAdmin) return
  userLoading.value = true
  try {
    const res: any = await userApi.listUsers({ current: 1, size: 20, keyword: query })
    userOptions.value = res?.records || res?.data?.records || []
  } finally {
    userLoading.value = false
  }
}

async function loadDeptOptions() {
  if (!userStore.isAdmin) return
  try {
    const res: any = await departmentApi.list()
    deptOptions.value = res?.data || res || []
  } catch {}
}

// ─── 搜索 ───
async function search(goto?: number) {
  if (goto) filters.current = goto
  loading.value = true
  try {
    const params: any = {
      keyword: filters.keyword || undefined,
      userId: filters.userId,
      deptId: filters.deptId,
      onlyFlagged: filters.onlyFlagged || undefined,
      current: filters.current,
      size: filters.size,
    }
    if (dateRange.value && dateRange.value.length === 2) {
      params.from = dateRange.value[0]
      params.to = dateRange.value[1]
    }
    const res: any = await adminConvApi.search(params)
    records.value = res?.records || res?.data?.records || []
    total.value = res?.total || res?.data?.total || 0
  } catch (e: any) {
    ElMessage.error('搜索失败：' + (e?.message || ''))
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.keyword = ''
  filters.userId = undefined
  filters.deptId = undefined
  filters.onlyFlagged = false
  filters.current = 1
  dateRange.value = null
  search()
}

function highlight(s: string) {
  if (!filters.keyword) return s
  const k = filters.keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return s.replace(new RegExp(`(${k})`, 'gi'),
    '<mark style="background:#fef3c7;color:#92400e;padding:0 2px;border-radius:2px">$1</mark>')
}

// ─── 详情 ───
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ConvDetail | null>(null)
const flagNote = ref('')

async function openDetail(id: number) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res: any = await adminConvApi.detail(id)
    detail.value = res?.data || res
  } catch (e: any) {
    ElMessage.error('加载详情失败：' + (e?.message || ''))
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function flagConv() {
  if (!detail.value) return
  if (!flagNote.value.trim()) {
    ElMessage.warning('请填写标记原因')
    return
  }
  try {
    await adminConvApi.flag(detail.value.conversation.id, flagNote.value.trim())
    ElMessage.success('已标记为敏感')
    detail.value.conversation.isFlagged = 1
    detail.value.conversation.flagNote = flagNote.value.trim()
    flagNote.value = ''
    // 同步刷新列表项的标记状态
    const item = records.value.find(r => r.id === detail.value!.conversation.id)
    if (item) {
      item.isFlagged = 1
      item.flagNote = detail.value.conversation.flagNote
    }
  } catch (e: any) {
    ElMessage.error('标记失败：' + (e?.message || ''))
  }
}

async function unflagConv() {
  if (!detail.value) return
  await ElMessageBox.confirm('确定取消敏感标记？', '提示', { type: 'warning' })
  try {
    await adminConvApi.unflag(detail.value.conversation.id)
    ElMessage.success('已取消')
    detail.value.conversation.isFlagged = 0
    detail.value.conversation.flagNote = ''
    const item = records.value.find(r => r.id === detail.value!.conversation.id)
    if (item) { item.isFlagged = 0; item.flagNote = '' }
  } catch (e: any) {
    if (e === 'cancel' || (e?.message || '').includes('cancel')) return
    ElMessage.error('操作失败：' + (e?.message || ''))
  }
}

// ─── 检索过程 ───
const retrievalVisible = ref(false)
const currentRetrievalLog = ref<any>(null)

function parseRetrievalLog(raw?: string) {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch { return null }
}

function showRetrievalLog(m: any) {
  currentRetrievalLog.value = parseRetrievalLog(m.retrievalLog)
  retrievalVisible.value = true
}

function formatTime(t?: string) {
  if (!t) return ''
  const d = new Date(t)
  const pad = (n: number) => n < 10 ? '0' + n : '' + n
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  if (userStore.isAdmin) await loadDeptOptions()
  search()
})
</script>

<style scoped>
.conv-search-page {
  padding: 28px 32px 48px;
  height: 100%;
  overflow-y: auto;
}
.page-header { margin-bottom: 22px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #fef3c7, #fde68a); color: #b45309;
}
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 760px; line-height: 1.65; }

.filter-bar {
  display: flex; gap: 10px; align-items: center; flex-wrap: wrap;
  padding: 14px 16px; background: var(--bg-surface);
  border: 1px solid var(--line); border-radius: 10px;
  margin-bottom: 18px;
}

.user-opt { display: flex; justify-content: space-between; gap: 12px; }
.opt-sub { color: var(--ink-4); font-size: 11px; }

.loading-state, .empty-state {
  padding: 40px; text-align: center; color: var(--ink-4);
}

.conv-list { display: flex; flex-direction: column; gap: 10px; }

.conv-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px 18px;
  cursor: pointer;
  transition: all 0.15s;
}
.conv-card:hover {
  border-color: var(--brand);
  box-shadow: 0 2px 12px rgba(56, 189, 248, 0.12);
  transform: translateY(-1px);
}
.conv-card.flagged {
  border-color: rgba(239, 68, 68, 0.5);
  background: rgba(239, 68, 68, 0.03);
}

.conv-head { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 6px; }
.conv-title {
  flex: 1; font-size: 14.5px; font-weight: 600; color: var(--ink-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.conv-meta { display: flex; gap: 8px; align-items: center; flex-shrink: 0; }
.meta-tag { font-size: 11.5px; color: var(--ink-4); padding: 2px 8px; background: var(--bg-hover); border-radius: 4px; }
.meta-time { font-size: 11.5px; color: var(--ink-4); }

.conv-user {
  display: flex; align-items: center; gap: 4px;
  font-size: 12.5px; color: var(--ink-3); margin-bottom: 4px;
}
.user-name { font-weight: 500; color: var(--ink-2); }
.user-dept, .flag-by { color: var(--ink-4); }

.conv-flag-note {
  display: flex; align-items: center; gap: 6px;
  margin: 6px 0;
  padding: 6px 10px;
  background: rgba(239, 68, 68, 0.08);
  color: #b91c1c;
  border-radius: 4px;
  font-size: 12px;
}

.conv-snippets {
  margin-top: 8px;
  padding: 8px 12px;
  background: var(--bg-hover);
  border-radius: 6px;
  font-size: 12.5px;
}
.snippet-row {
  display: flex; gap: 8px; align-items: baseline; line-height: 1.6;
}
.snippet-role {
  flex-shrink: 0;
  font-size: 11px; font-weight: 700;
  padding: 1px 6px; border-radius: 4px;
}
.snippet-row.user .snippet-role { background: rgba(56, 189, 248, 0.15); color: #0284c7; }
.snippet-row.assistant .snippet-role { background: rgba(124, 58, 237, 0.15); color: #6d28d9; }
.snippet-text { flex: 1; color: var(--ink-2); }

.pagination-bar {
  display: flex; justify-content: center;
  margin-top: 20px;
}

/* ── 详情抽屉 ── */
.detail-loading {
  padding: 40px; text-align: center; color: var(--ink-4);
}
.detail-content { padding: 0 4px; }
.detail-head {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 16px;
}
.detail-title {
  font-size: 17px; font-weight: 700; margin-bottom: 8px;
  color: var(--ink-1);
}
.detail-meta {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; color: var(--ink-2);
  margin-bottom: 4px;
}
.dim { color: var(--ink-4); }
.flag-section { margin-top: 14px; }

.detail-messages { display: flex; flex-direction: column; gap: 14px; }
.msg-row { display: flex; gap: 12px; align-items: flex-start; }
.msg-role-tag {
  flex-shrink: 0;
  width: 44px; padding: 4px 0; text-align: center;
  font-size: 11px; font-weight: 700;
  border-radius: 4px;
}
.msg-row.user .msg-role-tag { background: rgba(56, 189, 248, 0.15); color: #0284c7; }
.msg-row.assistant .msg-role-tag { background: rgba(124, 58, 237, 0.15); color: #6d28d9; }
.msg-body { flex: 1; min-width: 0; }
.msg-text {
  font-size: 13.5px; line-height: 1.7; color: var(--ink-1);
  white-space: pre-wrap; word-break: break-word;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 14px;
}
.msg-foot {
  display: flex; gap: 10px; align-items: center;
  margin-top: 4px;
}
.msg-time { font-size: 11.5px; color: var(--ink-4); }

.rl-json {
  max-height: 480px; overflow: auto;
  font-size: 12px; line-height: 1.6;
  background: var(--bg-hover);
  padding: 12px; border-radius: 6px;
}
</style>
