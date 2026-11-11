<template>
  <div class="gp-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">Golden Pair 库</h2>
          <span class="title-tag">人工校正的标准问答</span>
        </div>
        <p class="page-desc">
          已审核认可的标准问答对。用户问相似问题时（cosine 相似度 ≥ 阈值），系统直接命中返回此处答案，
          跳过完整 RAG 流程，保证"已纠正过的问题不会再答错"。
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建标准问答</el-button>
    </header>

    <div class="filter-bar">
      <el-input
        v-model="keyword"
        placeholder="搜索问题或答案"
        clearable
        style="width:280px"
        :prefix-icon="Search"
        @change="loadList"
      />
      <el-select v-model="filterEnabled" placeholder="状态" clearable style="width:120px" @change="loadList">
        <el-option label="启用中"  :value="1" />
        <el-option label="已禁用"  :value="0" />
      </el-select>
      <el-button :icon="Refresh" @click="loadList" />
    </div>

    <div class="gp-list" v-loading="loading">
      <article v-for="p in list" :key="p.id" class="gp-card" :class="{ disabled: p.enabled === 0 }">
        <header class="gp-head">
          <div class="gp-id">#{{ p.id }}</div>
          <div class="hit-count" v-if="p.hitCount && p.hitCount > 0">
            <el-icon size="11"><Aim /></el-icon>
            命中 {{ p.hitCount }} 次
          </div>
          <div class="gp-actions">
            <el-switch :model-value="p.enabled === 1" @change="(v: string | number | boolean) => toggleEnabled(p, v as boolean)" />
            <el-tooltip content="编辑"><button class="icon-btn" @click="openEdit(p)"><el-icon><Edit /></el-icon></button></el-tooltip>
            <el-tooltip content="删除"><button class="icon-btn danger" @click="del(p)"><el-icon><Delete /></el-icon></button></el-tooltip>
          </div>
        </header>
        <div class="gp-q"><span class="lbl">Q</span>{{ p.question }}</div>
        <div class="gp-a"><span class="lbl">A</span>{{ p.standardAnswer }}</div>
        <footer class="gp-foot">
          <span v-if="p.lastHitAt">最近命中 {{ formatTime(p.lastHitAt) }}</span>
          <span v-else class="never">尚未被命中</span>
        </footer>
      </article>

      <div v-if="!loading && list.length === 0" class="empty">
        <div class="empty-ring"></div>
        <p>暂无标准问答对</p>
        <p class="empty-sub">用户在 chat 里点踩 + 提供正确答案 → 审核员收录后会出现在这里</p>
      </div>
    </div>

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

    <!-- 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing.id ? `编辑 #${editing.id}` : '新建标准问答'"
      width="720px"
      :close-on-click-modal="false"
    >
      <div class="form-block">
        <label>问题 <span class="req">*</span></label>
        <el-input v-model="editing.question" type="textarea" :rows="2" placeholder="用户的问题（用于向量匹配）" maxlength="500" />
      </div>
      <div class="form-block">
        <label>标准答案 <span class="req">*</span></label>
        <el-input v-model="editing.answer" type="textarea" :autosize="{ minRows: 8, maxRows: 18 }" placeholder="审核员认可的标准答案" maxlength="5000" show-word-limit />
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Refresh, Search, Aim } from '@element-plus/icons-vue'
import { goldenPairApi, type GoldenPair } from '@/api/goldenPair'

const loading = ref(false)
const list = ref<GoldenPair[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const filterEnabled = ref<number | undefined>(undefined)

async function loadList() {
  loading.value = true
  try {
    const res: any = await goldenPairApi.page({
      current: currentPage.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      enabled: filterEnabled.value,
    })
    const data = res?.data ?? res
    list.value = data?.records || []
    total.value = data?.total || 0
  } finally { loading.value = false }
}

onMounted(loadList)

// 编辑/新建
const dialogVisible = ref(false)
const submitting = ref(false)
const editing = reactive<{ id?: number; question: string; answer: string }>({ question: '', answer: '' })

function openCreate() {
  editing.id = undefined
  editing.question = ''
  editing.answer = ''
  dialogVisible.value = true
}

function openEdit(p: GoldenPair) {
  editing.id = p.id
  editing.question = p.question
  editing.answer = p.standardAnswer
  dialogVisible.value = true
}

async function save() {
  if (!editing.question.trim() || !editing.answer.trim()) {
    ElMessage.warning('问题和答案都必填')
    return
  }
  submitting.value = true
  try {
    if (editing.id) {
      await goldenPairApi.update(editing.id, { question: editing.question, answer: editing.answer })
      ElMessage.success('已更新')
    } else {
      await goldenPairApi.create({ question: editing.question, answer: editing.answer })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await loadList()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { submitting.value = false }
}

async function toggleEnabled(p: GoldenPair, v: boolean) {
  try {
    await goldenPairApi.update(p.id, { enabled: v ? 1 : 0 })
    p.enabled = v ? 1 : 0
    ElMessage.success(v ? '已启用' : '已禁用')
  } catch (e: any) {
    ElMessage.error('切换失败：' + (e?.message || ''))
  }
}

async function del(p: GoldenPair) {
  try {
    await ElMessageBox.confirm(`确认删除该标准问答？相关 Milvus 向量也会一并清理。`, '警告', { type: 'warning' })
    await goldenPairApi.delete(p.id)
    ElMessage.success('已删除')
    await loadList()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

function formatTime(t?: string) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.gp-page { padding: 28px 32px 48px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 24px; flex-wrap: wrap; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); }
.title-tag { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #d1fae5, #a7f3d0); color: #047857; }
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }

.filter-bar { display: flex; gap: 10px; margin-bottom: 18px; align-items: center; }

.gp-list { display: flex; flex-direction: column; gap: 14px; }
.gp-card { background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px;
  padding: 16px 20px; transition: box-shadow 0.15s; }
.gp-card:hover { box-shadow: var(--shadow-md); }
.gp-card.disabled { opacity: 0.55; }
.gp-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.gp-id { font-family: 'JetBrains Mono', monospace; font-size: 11.5px; color: var(--ink-4); }
.hit-count { display: inline-flex; align-items: center; gap: 4px; padding: 2px 9px; border-radius: 999px;
  background: rgba(56, 189, 248, 0.08); border: 1px solid rgba(56, 189, 248, 0.2);
  color: #38bdf8; font-size: 11px; font-weight: 600; }
.gp-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.icon-btn { width: 28px; height: 28px; border-radius: 6px; background: transparent;
  border: 1px solid transparent; color: var(--ink-3); display: inline-flex; align-items: center; justify-content: center; cursor: pointer; }
.icon-btn:hover { background: var(--bg-hover); border-color: var(--line); color: var(--ink-1); }
.icon-btn.danger:hover { background: rgba(239,68,68,0.1); border-color: #ef4444; color: #ef4444; }

.gp-q, .gp-a { display: flex; gap: 10px; font-size: 13.5px; line-height: 1.6; margin-bottom: 8px; }
.gp-q .lbl, .gp-a .lbl { flex-shrink: 0; width: 22px; height: 22px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center; font-weight: 700; font-size: 11px;
  font-family: 'Manrope', sans-serif; }
.gp-q .lbl { background: rgba(167,139,250,0.15); color: #7c3aed; }
.gp-a .lbl { background: rgba(52,211,153,0.15); color: #047857; }
.gp-q { color: var(--ink-1); font-weight: 600; }
.gp-a { color: var(--ink-2); white-space: pre-wrap; }

.gp-foot { font-size: 11.5px; color: var(--ink-4); margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--line); }
.never { font-style: italic; }

.empty { padding: 80px 0; text-align: center; color: var(--ink-3); }
.empty-ring { width: 48px; height: 48px; border: 3px solid var(--line); border-top-color: var(--brand);
  border-radius: 50%; margin: 0 auto 14px; }
.empty-sub { font-size: 11.5px; color: var(--ink-4); margin-top: 6px; }

.form-block { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
.form-block label { font-size: 12.5px; font-weight: 600; color: var(--ink-2); }
.req { color: var(--danger); }

.pagination { margin-top: 20px; text-align: center; }
</style>
