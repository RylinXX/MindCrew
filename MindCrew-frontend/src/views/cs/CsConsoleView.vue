<template>
  <div class="cs-console">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">客服工作台</h2>
          <span class="title-tag">人工坐席 · 会话接管</span>
        </div>
        <p class="page-desc">
          查看 AI 客服会话，认领后接管对话；支持人工回复、转接同事、关闭会话。
          列表每 30 秒自动刷新，访客侧实时收到你的回复。
        </p>
      </div>
      <div class="header-actions">
        <span class="auto-hint">
          <span class="auto-dot"></span>
          自动刷新 {{ autoRefresh ? '开启' : '关闭' }}
        </span>
        <el-switch v-model="autoRefresh" />
        <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
      </div>
    </header>

    <!-- KPI -->
    <section class="kpi-row">
      <div class="kpi-card" v-for="kpi in kpiCards" :key="kpi.label">
        <span class="kpi-label">{{ kpi.label }}</span>
        <span class="kpi-value" :class="kpi.tone">{{ kpi.value }}</span>
        <span class="kpi-sub">{{ kpi.sub }}</span>
      </div>
    </section>

    <!-- 主体 -->
    <section class="console-body">
      <!-- 左：会话列表 -->
      <aside class="session-panel">
        <div class="panel-head">
          <div class="filter-row">
            <el-select v-model="filters.status" placeholder="状态" size="small" style="width: 110px" @change="reloadList">
              <el-option label="全部状态" value="" />
              <el-option label="机器人在答" value="bot" />
              <el-option label="人工处理中" value="human" />
              <el-option label="已关闭" value="closed" />
            </el-select>
            <el-select v-model="filters.channel" placeholder="渠道" size="small" style="width: 110px" @change="reloadList">
              <el-option label="全部渠道" value="" />
              <el-option label="网页" value="web" />
              <el-option label="钉钉" value="dingtalk" />
              <el-option label="API" value="api" />
              <el-option label="嵌入式" value="embed" />
            </el-select>
          </div>
          <el-input
            v-model="filters.keyword"
            size="small"
            clearable
            :prefix-icon="Search"
            placeholder="搜索访客 / 会话号 / 内容"
            @keyup.enter="reloadList"
            @clear="reloadList"
          />
        </div>

        <div class="session-list" v-loading="listLoading">
          <button
            v-for="s in sessions"
            :key="s.id"
            class="session-item"
            :class="{ active: currentId === s.id }"
            @click="openSession(s.id)"
          >
            <div class="session-top">
              <span class="session-name">{{ displayVisitor(s) }}</span>
              <span class="status-pill" :class="s.status">{{ statusLabel(s.status) }}</span>
            </div>
            <div class="session-last">{{ s.lastMessage || '（暂无消息）' }}</div>
            <div class="session-meta">
              <span class="channel-chip">{{ channelLabel(s.channel) }}</span>
              <span v-if="s.assigneeName" class="assignee">· {{ s.assigneeName }}</span>
              <span class="time">{{ formatTime(s.lastActive || s.createTime) }}</span>
            </div>
          </button>

          <div v-if="!listLoading && sessions.length === 0" class="empty-list">
            <el-icon :size="26"><ChatLineSquare /></el-icon>
            <p>没有符合条件的会话</p>
          </div>
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="page.current"
            v-model:page-size="page.size"
            :total="page.total"
            :page-sizes="[10, 20, 50]"
            layout="prev, pager, next"
            small
            background
            @current-change="reloadList"
          />
        </div>
      </aside>

      <!-- 右：会话详情 -->
      <section class="detail-panel" v-loading="detailLoading">
        <template v-if="detail">
          <header class="detail-head">
            <div class="detail-head-left">
              <div class="detail-avatar">{{ displayVisitor(detail.session).slice(0, 1) }}</div>
              <div>
                <div class="detail-title">
                  {{ displayVisitor(detail.session) }}
                  <span class="status-pill" :class="detail.session.status">{{ statusLabel(detail.session.status) }}</span>
                </div>
                <div class="detail-sub">
                  会话号 {{ detail.session.sessionNo }}
                  · {{ channelLabel(detail.session.channel) }}
                  · 消息 {{ detail.session.messageCount ?? detail.messages.length }}
                  <template v-if="detail.session.assigneeName || detail.assigneeName">
                    · 坐席 {{ detail.session.assigneeName || detail.assigneeName }}
                  </template>
                </div>
              </div>
            </div>
            <div class="detail-actions">
              <el-button
                size="small"
                type="primary"
                :disabled="detail.session.status === 'closed'"
                :loading="acting === 'claim'"
                @click="claimSession"
              >认领</el-button>
              <el-button
                size="small"
                :disabled="detail.session.status === 'closed'"
                :loading="acting === 'transfer'"
                @click="openTransfer"
              >转接</el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="detail.session.status === 'closed'"
                :loading="acting === 'close'"
                @click="closeSession"
              >关闭</el-button>
            </div>
          </header>

          <!-- 会话画像 -->
          <div class="session-brief">
            <div class="brief-item"><span>访客标识</span><strong>{{ detail.session.visitorKey || '—' }}</strong></div>
            <div class="brief-item"><span>来源</span><strong>{{ detail.session.sourceAppId || '站内访客' }}</strong></div>
            <div class="brief-item"><span>会话状态</span><strong>{{ statusLabel(detail.session.status) }}</strong></div>
            <div class="brief-item" v-if="detail.session.rating">
              <span>满意度</span><strong>{{ detail.session.rating }} / 5</strong>
            </div>
          </div>

          <!-- 消息流 -->
          <div class="detail-messages" ref="messageArea">
            <div
              v-for="m in detail.messages"
              :key="m.id"
              class="wb-msg"
              :class="m.role"
            >
              <template v-if="m.role === 'system'">
                <span class="wb-system">{{ m.content }}</span>
              </template>
              <template v-else>
                <div class="wb-avatar" :class="m.role">{{ roleInitial(m.role) }}</div>
                <div class="wb-bubble-wrap">
                  <div class="wb-meta">
                    <span>{{ roleLabel(m.role) }}</span>
                    <span>{{ formatTime(m.createTime) }}</span>
                  </div>
                  <div class="wb-bubble md-body" v-html="renderMessage(m)"></div>
                </div>
              </template>
            </div>
            <div v-if="detail.messages.length === 0" class="empty-messages">该会话还没有消息</div>
            <div ref="messageBottom"></div>
          </div>

          <!-- 回复框 -->
          <footer class="reply-box">
            <el-input
              v-model="replyText"
              type="textarea"
              :rows="3"
              resize="none"
              maxlength="2000"
              show-word-limit
              :disabled="detail.session.status === 'closed'"
              placeholder="输入回复，Ctrl + Enter 发送（Enter 换行）"
              @keydown.ctrl.enter.prevent="sendReply"
            />
            <div class="reply-actions">
              <span class="reply-hint" v-if="detail.session.status === 'closed'">会话已关闭，无法回复</span>
              <span class="reply-hint" v-else>回复会同时出现在访客页</span>
              <el-button
                type="primary"
                :disabled="detail.session.status === 'closed' || !replyText.trim()"
                :loading="acting === 'reply'"
                @click="sendReply"
              >发送回复</el-button>
            </div>
          </footer>
        </template>

        <div v-else class="detail-empty">
          <el-icon :size="40"><Headset /></el-icon>
          <p>从左侧选择一个会话开始处理</p>
        </div>
      </section>
    </section>

    <!-- 转接弹窗 -->
    <el-dialog v-model="transferVisible" title="转接会话" width="440px">
      <div class="transfer-body">
        <p class="transfer-hint">转接后该会话的坐席将变为对方，请确认对方在线。</p>
        <el-select
          v-if="agents.length"
          v-model="transferAssigneeId"
          filterable
          placeholder="选择坐席"
          style="width: 100%"
        >
          <el-option
            v-for="agent in agents"
            :key="agent.id"
            :label="`${agent.nickname || agent.username} (#${agent.id} · ${agent.role})`"
            :value="agent.id"
          />
        </el-select>
        <el-input-number v-else v-model="transferAssigneeId" :min="1" :step="1" placeholder="输入坐席用户 ID" style="width: 100%" />
        <p class="transfer-tip" v-if="!agents.length && !agentListLoaded">无法读取用户列表（非管理员），请手动输入坐席用户 ID。</p>
      </div>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!transferAssigneeId" @click="confirmTransfer">确认转接</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineSquare, Headset, Refresh, Search } from '@element-plus/icons-vue'
import { marked } from 'marked'
import {
  csConsoleApi,
  type CsMessage,
  type CsSession,
  type CsSessionDetail,
  type CsSessionStatus,
  type CsStats
} from '@/api/cs'
import { userApi } from '@/api/user'
import { useUserStore } from '@/stores/user'

marked.setOptions({ breaks: true, gfm: true })

interface AgentOption {
  id: number
  username: string
  nickname?: string
  role: string
}

const userStore = useUserStore()

const sessions = ref<CsSession[]>([])
const stats = ref<CsStats | null>(null)
const listLoading = ref(false)
const detailLoading = ref(false)
const detail = ref<CsSessionDetail | null>(null)
const currentId = ref<number | null>(null)
const autoRefresh = ref(true)
const acting = ref<'' | 'claim' | 'reply' | 'transfer' | 'close'>('')

const filters = ref({ status: '', channel: '', keyword: '' })
const page = ref({ current: 1, size: 10, total: 0 })

const replyText = ref('')
const transferVisible = ref(false)
const transferAssigneeId = ref<number>()
const agents = ref<AgentOption[]>([])
const agentListLoaded = ref(false)

const messageArea = ref<HTMLElement | null>(null)
const messageBottom = ref<HTMLElement | null>(null)

let autoTimer: number | undefined

const kpiCards = computed(() => {
  const s = stats.value
  return [
    { label: '会话总数', value: s?.sessions ?? 0, sub: '近 7 天新建', tone: '' },
    { label: 'AI 会话', value: s?.aiSessions ?? 0, sub: '触发过智能回答', tone: 'info' },
    { label: '转人工', value: s?.handoffCount ?? 0, sub: '坐席已接入', tone: 'warn' },
    { label: '转人工率', value: `${(s?.handoffRate ?? 0).toFixed(1)}%`, sub: '占会话总数', tone: 'warn' },
    { label: '平均满意度', value: (s?.avgRating ?? 0).toFixed(2), sub: '满分 5 分', tone: 'good' }
  ]
})

function displayVisitor(session: CsSession): string {
  return session.visitorName || session.visitorKey || `访客 #${session.id}`
}

function statusLabel(status?: CsSessionStatus | string): string {
  const map: Record<string, string> = { bot: '机器人在答', human: '人工处理中', closed: '已关闭' }
  return map[status || ''] || String(status || '—')
}

function channelLabel(channel?: string): string {
  const map: Record<string, string> = { web: '网页', dingtalk: '钉钉', api: 'API', embed: '嵌入式' }
  return map[channel || ''] || channel || '—'
}

function roleLabel(role: string): string {
  const map: Record<string, string> = { user: '访客', ai: 'AI 客服', agent: '人工坐席', system: '系统' }
  return map[role] || role
}

function roleInitial(role: string): string {
  return role === 'ai' ? 'AI' : role === 'agent' ? '客' : '访'
}

function formatTime(time?: string): string {
  if (!time) return ''
  const date = new Date(time.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function renderMessage(message: CsMessage): string {
  if (message.role === 'user') {
    return escapeHtml(message.content)
  }
  return marked.parse(message.content || '') as string
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

// ─────────────────────────────────────────────
// 数据加载
// ─────────────────────────────────────────────
async function loadStats() {
  try {
    stats.value = await csConsoleApi.stats(7)
  } catch {
    // 统计失败不影响主流程
  }
}

async function loadList(keepSelection = true) {
  listLoading.value = true
  try {
    const result = await csConsoleApi.sessions({
      current: page.value.current,
      size: page.value.size,
      status: filters.value.status || undefined,
      channel: filters.value.channel || undefined,
      keyword: filters.value.keyword.trim() || undefined
    })
    sessions.value = result.records || []
    page.value.total = result.total || 0
    page.value.current = result.current || page.value.current
    page.value.size = result.size || page.value.size

    if (!keepSelection || currentId.value == null) {
      const first = sessions.value[0]
      if (first) {
        await openSession(first.id)
      } else {
        detail.value = null
        currentId.value = null
      }
    } else if (!sessions.value.some(s => s.id === currentId.value)) {
      // 当前会话被过滤掉时保持右侧详情，不清空
    }
  } catch (e: any) {
    ElMessage.error('加载会话列表失败：' + (e?.message || ''))
  } finally {
    listLoading.value = false
  }
}

function reloadList() {
  page.value.current = 1
  loadList(true)
}

async function refreshAll() {
  await Promise.all([loadStats(), loadList(true)])
}

async function openSession(id: number) {
  currentId.value = id
  detailLoading.value = true
  try {
    detail.value = await csConsoleApi.detail(id)
    replyText.value = ''
    scrollMessages()
  } catch (e: any) {
    ElMessage.error('加载会话详情失败：' + (e?.message || ''))
  } finally {
    detailLoading.value = false
  }
}

function scrollMessages() {
  nextTick(() => {
    messageBottom.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  })
}

// ─────────────────────────────────────────────
// 操作
// ─────────────────────────────────────────────
async function claimSession() {
  if (!detail.value) return
  acting.value = 'claim'
  try {
    await csConsoleApi.claim(detail.value.session.id)
    ElMessage.success('已认领会话')
    await openSession(detail.value.session.id)
    await loadList(true)
  } catch (e: any) {
    ElMessage.error('认领失败：' + (e?.message || ''))
  } finally {
    acting.value = ''
  }
}

async function sendReply() {
  if (!detail.value || !replyText.value.trim()) return
  acting.value = 'reply'
  try {
    await csConsoleApi.reply(detail.value.session.id, replyText.value.trim())
    replyText.value = ''
    await openSession(detail.value.session.id)
    await loadList(true)
    ElMessage.success('已发送')
  } catch (e: any) {
    ElMessage.error('发送失败：' + (e?.message || ''))
  } finally {
    acting.value = ''
  }
}

async function loadAgents() {
  if (agentListLoaded.value) return
  agentListLoaded.value = true
  if (!userStore.isAdmin) return
  try {
    const result: any = await userApi.listUsers({ current: 1, size: 100 })
    const records: any[] = result?.records || result?.data?.records || []
    agents.value = records.map((u: any) => ({
      id: u.id,
      username: u.username,
      nickname: u.nickname,
      role: u.role
    }))
  } catch {
    // 非管理员时保持手动输入
  }
}

function openTransfer() {
  loadAgents()
  transferAssigneeId.value = detail.value?.session.assigneeId
  transferVisible.value = true
}

async function confirmTransfer() {
  if (!detail.value || !transferAssigneeId.value) return
  acting.value = 'transfer'
  try {
    await csConsoleApi.transfer(detail.value.session.id, transferAssigneeId.value)
    transferVisible.value = false
    ElMessage.success('已转接')
    await openSession(detail.value.session.id)
    await loadList(true)
  } catch (e: any) {
    ElMessage.error('转接失败：' + (e?.message || ''))
  } finally {
    acting.value = ''
  }
}

async function closeSession() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      `确认关闭「${displayVisitor(detail.value.session)}」的会话？关闭后访客将无法继续发送消息。`,
      '关闭会话',
      { type: 'warning' }
    )
  } catch {
    return
  }
  acting.value = 'close'
  try {
    await csConsoleApi.close(detail.value.session.id)
    ElMessage.success('会话已关闭')
    await openSession(detail.value.session.id)
    await loadList(true)
  } catch (e: any) {
    ElMessage.error('关闭失败：' + (e?.message || ''))
  } finally {
    acting.value = ''
  }
}

onMounted(async () => {
  await Promise.all([loadStats(), loadList(false)])
  autoTimer = window.setInterval(() => {
    if (!autoRefresh.value) return
    loadStats()
    loadList(true)
    if (detail.value && currentId.value != null) {
      csConsoleApi.detail(currentId.value).then(d => {
        // 仅当用户没有正在输入时静默刷新详情
        if (!replyText.value.trim()) detail.value = d
      }).catch(() => {})
    }
  }, 30_000)
})

onBeforeUnmount(() => {
  if (autoTimer !== undefined) {
    clearInterval(autoTimer)
    autoTimer = undefined
  }
})
</script>

<style scoped>
.cs-console {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 22px 28px 20px;
  background: var(--bg-page);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  flex-shrink: 0;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.page-title { font-family: 'Manrope', 'Noto Sans SC', sans-serif; font-size: 23px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #ffe4e6, #fecdd3); color: #be123c;
}
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 780px; line-height: 1.65; }
.header-actions { display: flex; align-items: center; gap: 10px; }
.auto-hint { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--ink-3); }
.auto-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--success); }

.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
  flex-shrink: 0;
}
.kpi-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 13px 16px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  box-shadow: var(--shadow-card);
}
.kpi-label { font-size: 11.5px; color: var(--ink-3); }
.kpi-value { font-family: 'Manrope', 'Noto Sans SC', sans-serif; font-size: 22px; font-weight: 800; color: var(--ink-1); line-height: 1.25; }
.kpi-value.info { color: var(--info-ink); }
.kpi-value.warn { color: var(--warning-ink); }
.kpi-value.good { color: var(--success-ink); }
.kpi-sub { font-size: 10.5px; color: var(--ink-4); }

.console-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 14px;
  overflow: hidden;
}

.session-panel {
  width: 330px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  overflow: hidden;
}
.panel-head { padding: 12px; border-bottom: 1px solid var(--line-soft); display: flex; flex-direction: column; gap: 8px; }
.filter-row { display: flex; gap: 8px; }
.session-list { flex: 1; min-height: 0; overflow-y: auto; padding: 8px; display: flex; flex-direction: column; gap: 6px; }
.session-item {
  text-align: left;
  border: 1px solid transparent;
  background: var(--bg-page);
  border-radius: 10px;
  padding: 10px 12px;
  cursor: pointer;
  transition: all 160ms ease;
}
.session-item:hover { border-color: var(--line-strong); background: var(--bg-subtle); }
.session-item.active { border-color: var(--brand); background: var(--brand-soft); }
.session-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.session-name { font-size: 13px; font-weight: 600; color: var(--ink-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-last {
  font-size: 12px; color: var(--ink-3); margin-top: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.session-meta { display: flex; align-items: center; gap: 6px; margin-top: 6px; font-size: 10.5px; color: var(--ink-4); }
.channel-chip { background: var(--bg-hover); border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; }
.assignee { color: var(--ink-3); }
.session-meta .time { margin-left: auto; }
.status-pill { font-size: 10px; font-weight: 600; border-radius: 999px; padding: 2px 8px; flex-shrink: 0; }
.status-pill.bot { background: var(--info-soft); color: var(--info-ink); }
.status-pill.human { background: var(--warning-soft); color: var(--warning-ink); }
.status-pill.closed { background: var(--bg-hover); color: var(--ink-3); }
.empty-list { padding: 40px 10px; text-align: center; color: var(--ink-4); font-size: 12.5px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.pager { border-top: 1px solid var(--line-soft); padding: 8px; display: flex; justify-content: center; }

.detail-panel {
  flex: 1;
  min-width: 0;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.detail-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 14px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line-soft);
  flex-wrap: wrap;
}
.detail-head-left { display: flex; gap: 10px; align-items: center; min-width: 0; }
.detail-avatar {
  width: 38px; height: 38px; border-radius: 50%;
  background: linear-gradient(135deg, #FF8A65, #F43F5E);
  color: #fff; font-weight: 700; font-size: 15px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.detail-title { font-size: 15px; font-weight: 700; color: var(--ink-1); display: flex; align-items: center; gap: 8px; }
.detail-sub { font-size: 11.5px; color: var(--ink-3); margin-top: 3px; }
.detail-actions { display: flex; gap: 8px; flex-shrink: 0; }

.session-brief {
  display: flex; gap: 18px; flex-wrap: wrap;
  padding: 10px 18px;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--line-soft);
}
.brief-item { display: flex; flex-direction: column; gap: 1px; min-width: 110px; }
.brief-item span { font-size: 10.5px; color: var(--ink-4); }
.brief-item strong { font-size: 12.5px; color: var(--ink-2); font-weight: 600; overflow-wrap: anywhere; }

.detail-messages {
  flex: 1; min-height: 0; overflow-y: auto;
  padding: 16px 20px 8px;
  display: flex; flex-direction: column; gap: 14px;
}
.wb-msg { display: flex; gap: 9px; align-items: flex-start; }
.wb-msg.user { justify-content: flex-start; }
.wb-msg.agent, .wb-msg.ai { justify-content: flex-end; flex-direction: row-reverse; }
.wb-msg.system { justify-content: center; }
.wb-system { font-size: 11.5px; color: var(--ink-3); background: var(--bg-subtle); border: 1px solid var(--line); border-radius: 999px; padding: 4px 12px; }
.wb-avatar {
  width: 30px; height: 30px; border-radius: 50%;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  color: var(--ink-3);
  font-size: 11px; font-weight: 700;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.wb-avatar.ai { background: var(--brand-soft); color: var(--brand); border-color: var(--brand-soft-2); }
.wb-avatar.agent { background: var(--success-soft); color: var(--success-ink); border-color: #BBF7D0; }
.wb-bubble-wrap { display: flex; flex-direction: column; gap: 3px; max-width: min(76%, 640px); min-width: 0; }
.wb-msg.agent .wb-bubble-wrap, .wb-msg.ai .wb-bubble-wrap { align-items: flex-end; }
.wb-meta { display: flex; gap: 8px; font-size: 10.5px; color: var(--ink-4); }
.wb-bubble {
  padding: 9px 13px;
  border-radius: 12px;
  font-size: 13.5px;
  line-height: 1.7;
  background: var(--bg-subtle);
  color: var(--ink-1);
  word-break: break-word;
}
.wb-msg.user .wb-bubble { border-top-left-radius: 4px; }
.wb-msg.agent .wb-bubble { background: var(--brand); color: #fff; border-top-right-radius: 4px; }
.wb-msg.ai .wb-bubble { background: var(--brand-soft); color: var(--ink-1); border-top-right-radius: 4px; }
.empty-messages { text-align: center; color: var(--ink-4); font-size: 12.5px; padding: 30px 0; }

.reply-box { border-top: 1px solid var(--line); padding: 12px 16px 14px; display: flex; flex-direction: column; gap: 9px; }
.reply-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.reply-hint { font-size: 11.5px; color: var(--ink-4); margin-right: auto; }

.detail-empty {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; color: var(--ink-4); font-size: 13px;
}
.transfer-body { display: flex; flex-direction: column; gap: 12px; }
.transfer-hint { font-size: 12.5px; color: var(--ink-3); }
.transfer-tip { font-size: 11.5px; color: var(--ink-4); }

.md-body :deep(p) { margin: 0 0 5px; }
.md-body :deep(p:last-child) { margin-bottom: 0; }
.md-body :deep(ul), .md-body :deep(ol) { padding-left: 18px; margin: 4px 0; }
.md-body :deep(code) { font-family: 'JetBrains Mono', monospace; font-size: 12px; background: rgba(0, 0, 0, 0.06); padding: 1px 5px; border-radius: 4px; }
.md-body :deep(pre) { background: rgba(0, 0, 0, 0.06); border-radius: 8px; padding: 8px 10px; overflow-x: auto; }
.md-body :deep(pre code) { background: none; padding: 0; }
.md-body :deep(a) { text-decoration: underline; color: inherit; }

@media (max-width: 1080px) {
  .kpi-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .session-panel { width: 280px; }
}
</style>
