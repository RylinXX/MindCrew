<template>
  <div class="audit-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">审计日志</h2>
          <span class="title-tag">任务 12 · 合规追溯</span>
        </div>
        <p class="page-desc">记录所有关键操作（登录、上传、删除、权限变更等），合规审计与安全溯源。</p>
      </div>
    </header>

    <!-- 过滤栏 -->
    <div class="filter-bar">
      <el-input v-model="filterAction" placeholder="动作 code（如 kb.upload）" clearable style="width:200px" @change="reload" />
      <el-select v-model="filterTargetType" placeholder="目标类型" clearable style="width:140px" @change="reload">
        <el-option label="知识库" value="kb" />
        <el-option label="用户"   value="user" />
        <el-option label="API Key" value="api_key" />
        <el-option label="Golden Pair" value="golden_pair" />
        <el-option label="人格"   value="persona" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="状态" clearable style="width:120px" @change="reload">
        <el-option label="成功" value="success" />
        <el-option label="失败" value="failure" />
      </el-select>
      <el-date-picker v-model="dateRange" type="daterange" size="default"
                       value-format="YYYY-MM-DD" start-placeholder="起始" end-placeholder="结束"
                       @change="reload" />
      <el-button :icon="Refresh" @click="reload" />
      <el-button type="primary" :icon="Download" @click="downloadCsv">导出 CSV</el-button>
    </div>

    <!-- 表格 -->
    <el-table :data="rows" v-loading="loading" stripe size="small" class="audit-table">
      <el-table-column prop="createdAt" label="时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="120">
        <template #default="{ row }">
          <span class="op-user">{{ row.username || (row.userId != null ? `#${row.userId}` : '系统') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="动作" width="200">
        <template #default="{ row }">
          <div class="action-cell">
            <span class="action-label">{{ row.actionLabel || row.action }}</span>
            <code class="action-code">{{ row.action }}</code>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="目标" width="180">
        <template #default="{ row }">
          <div v-if="row.targetType">
            <span class="target-type">{{ row.targetType }}</span>
            <span class="target-id">#{{ row.targetId }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <span class="status-tag" :class="`s-${row.status}`">{{ row.status }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="130" />
      <el-table-column prop="latencyMs" label="耗时" width="80" align="right">
        <template #default="{ row }">{{ row.latencyMs }}ms</template>
      </el-table-column>
      <el-table-column label="详情">
        <template #default="{ row }">
          <button class="detail-btn" @click="openDetail(row)">查看</button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="current"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        background
        layout="total, sizes, prev, pager, next"
        @change="reload"
      />
    </div>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="审计日志详情" width="680px">
      <div v-if="currentDetail" class="detail-grid">
        <div><span class="dk">时间</span><span>{{ formatTime(currentDetail.createdAt) }}</span></div>
        <div><span class="dk">操作人</span><span>{{ currentDetail.username }} (#{{ currentDetail.userId }})</span></div>
        <div><span class="dk">动作</span><span>{{ currentDetail.actionLabel }} <code>{{ currentDetail.action }}</code></span></div>
        <div><span class="dk">目标</span><span>{{ currentDetail.targetType }} #{{ currentDetail.targetId }}</span></div>
        <div><span class="dk">状态</span><span :class="`s-${currentDetail.status}`">{{ currentDetail.status }}</span></div>
        <div><span class="dk">IP</span><span>{{ currentDetail.ip }}</span></div>
        <div><span class="dk">耗时</span><span>{{ currentDetail.latencyMs }}ms</span></div>
        <div v-if="currentDetail.userAgent" class="ua-row"><span class="dk">User-Agent</span><span class="ua">{{ currentDetail.userAgent }}</span></div>
        <div v-if="currentDetail.errorMsg" class="err-row"><span class="dk">错误</span><span class="err">{{ currentDetail.errorMsg }}</span></div>
      </div>
      <div class="detail-json-block" v-if="currentDetail?.detailJson">
        <div class="dk" style="margin-bottom:6px">参数 / 响应（已脱敏）</div>
        <pre>{{ prettyJson(currentDetail.detailJson) }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Download } from '@element-plus/icons-vue'
import { auditApi, type AuditLog } from '@/api/audit'

const loading = ref(false)
const rows = ref<AuditLog[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(20)
const filterAction = ref('')
const filterTargetType = ref('')
const filterStatus = ref('')
const dateRange = ref<[string, string] | null>(null)

const reload = async () => {
  loading.value = true
  try {
    const res: any = await auditApi.page({
      current: current.value,
      size: size.value,
      action: filterAction.value || undefined,
      targetType: filterTargetType.value || undefined,
      status: filterStatus.value || undefined,
      from: dateRange.value?.[0],
      to:   dateRange.value?.[1],
    })
    const d = res?.data ?? res
    rows.value = d?.records || []
    total.value = d?.total || 0
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally {
    loading.value = false
  }
}

onMounted(reload)

const downloadCsv = () => {
  const url = auditApi.exportCsvUrl({
    action: filterAction.value || undefined,
    targetType: filterTargetType.value || undefined,
    status: filterStatus.value || undefined,
    from: dateRange.value?.[0],
    to:   dateRange.value?.[1],
  })
  // 用 fetch 带 token，下载得到的 blob
  fetch(url, {
    headers: { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') }
  }).then(r => {
    if (!r.ok) throw new Error('HTTP ' + r.status)
    return r.blob()
  }).then(blob => {
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'audit-log.csv'
    a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success('已下载')
  }).catch((e) => ElMessage.error('下载失败：' + e.message))
}

const detailVisible = ref(false)
const currentDetail = ref<AuditLog | null>(null)
const openDetail = (row: AuditLog) => {
  currentDetail.value = row
  detailVisible.value = true
}

const formatTime = (t?: string) => {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}
const prettyJson = (s?: string) => {
  if (!s) return ''
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}
</script>

<style scoped>
.audit-page { padding: 24px 28px 40px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { margin-bottom: 20px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.page-title { font-size: 22px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #fef3c7, #fde68a); color: #b45309;
}
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 720px; line-height: 1.55; }

.filter-bar { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; flex-wrap: wrap; }

.audit-table { background: var(--bg-surface); border-radius: 10px; }
.op-user { font-weight: 600; color: var(--ink-1); }
.action-cell { display: flex; flex-direction: column; gap: 2px; }
.action-label { font-size: 13px; color: var(--ink-1); font-weight: 500; }
.action-code { font-family: 'JetBrains Mono', monospace; font-size: 10.5px; color: var(--ink-4); }
.target-type {
  font-size: 11px; padding: 1px 7px; border-radius: 4px;
  background: var(--brand-soft); color: var(--brand); font-weight: 600;
}
.target-id { font-family: 'JetBrains Mono', monospace; font-size: 11.5px; color: var(--ink-3); margin-left: 4px; }
.status-tag { font-size: 10.5px; font-weight: 700; padding: 2px 8px; border-radius: 999px; }
.status-tag.s-success { background: rgba(52,211,153,0.15); color: #047857; }
.status-tag.s-failure { background: rgba(248,113,113,0.15); color: #b91c1c; }
.detail-btn { background: none; border: none; color: var(--brand); cursor: pointer; font-size: 12.5px; }
.detail-btn:hover { text-decoration: underline; }

.pagination { margin-top: 18px; display: flex; justify-content: flex-end; }

.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 18px; margin-bottom: 16px; }
.detail-grid > div { display: flex; gap: 10px; font-size: 13px; }
.detail-grid .ua-row, .detail-grid .err-row { grid-column: 1 / -1; }
.dk { color: var(--ink-3); font-weight: 600; min-width: 80px; }
.ua { font-family: 'JetBrains Mono', monospace; font-size: 11px; word-break: break-all; }
.err { color: #b91c1c; }
.detail-json-block pre {
  background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px;
  font-family: 'JetBrains Mono', monospace; font-size: 11.5px; line-height: 1.55;
  overflow-x: auto; max-height: 280px;
}
.s-success { color: #047857; font-weight: 600; }
.s-failure { color: #b91c1c; font-weight: 600; }
</style>
