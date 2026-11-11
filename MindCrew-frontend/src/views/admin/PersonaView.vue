<template>
  <div class="persona-page">
    <!-- 顶部 -->
    <header class="page-header">
      <div class="page-header-l">
        <div class="title-row">
          <h2 class="page-title">Soul 人格管理</h2>
          <span class="title-tag">反讨好型 · 性格定义</span>
        </div>
        <p class="page-desc">
          定义 AI 在面向用户回答时的"性格""语气""底线"。所有人格默认追加全局反讨好规则，
          确保不会沦为讨好型 AI。
        </p>
      </div>
      <div class="page-header-r">
        <button class="btn-primary" @click="openCreate">
          <el-icon :size="14"><Plus /></el-icon>
          新建人格
        </button>
      </div>
    </header>

    <!-- 人格卡片网格 -->
    <main class="persona-grid" v-loading="loading">
      <article
        v-for="p in personas"
        :key="p.id"
        class="persona-card"
        :class="{ 'is-default': p.isDefault === 1 }"
      >
        <header class="card-head">
          <div class="card-title-row">
            <h3 class="card-title">{{ p.name }}</h3>
            <span v-if="p.isDefault === 1" class="badge default">默认</span>
            <span v-if="p.antiSycophancy === 1" class="badge anti">反讨好</span>
          </div>
          <div class="card-actions">
            <el-tooltip content="编辑" placement="top">
              <button class="icon-btn" @click="openEdit(p)">
                <el-icon :size="14"><Edit /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip v-if="p.isDefault !== 1" content="设为默认" placement="top">
              <button class="icon-btn" @click="setDefault(p)">
                <el-icon :size="14"><StarFilled /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <button class="icon-btn danger" @click="handleDelete(p)">
                <el-icon :size="14"><Delete /></el-icon>
              </button>
            </el-tooltip>
          </div>
        </header>

        <p class="card-desc">{{ p.description || '（无描述）' }}</p>

        <div class="card-meta">
          <span class="meta-chip">
            <el-icon :size="11"><CaretRight /></el-icon>
            温度 {{ Number(p.temperature).toFixed(2) }}
          </span>
          <span v-if="p.modelName" class="meta-chip">
            <el-icon :size="11"><Cpu /></el-icon>
            {{ p.modelName }}
          </span>
        </div>

        <div class="card-preview">
          <div class="preview-label">SYSTEM PROMPT</div>
          <div class="preview-text">{{ truncate(p.systemPrompt, 180) }}</div>
        </div>
      </article>

      <!-- 空态 -->
      <div v-if="!loading && personas.length === 0" class="empty">
        <div class="empty-ring"></div>
        <p>暂无人格，点右上角新建</p>
      </div>
    </main>

    <!-- 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing.id ? '编辑人格：' + editing.name : '新建人格'"
      width="780px"
      :close-on-click-modal="false"
      class="persona-dialog"
    >
      <div class="form-grid">
        <div class="form-row">
          <label>名称 <span class="req">*</span></label>
          <el-input v-model="editing.name" placeholder="如：法务严谨型" maxlength="50" />
        </div>
        <div class="form-row">
          <label>简短描述</label>
          <el-input v-model="editing.description" placeholder="该人格在哪些场景使用，给管理员看的" maxlength="200" />
        </div>

        <div class="form-row">
          <label>System Prompt（性格定义）<span class="req">*</span></label>
          <el-input
            v-model="editing.systemPrompt"
            type="textarea"
            :rows="14"
            placeholder="定义 AI 的语气、专业领域、回答风格、底线..."
          />
          <div class="form-hint">
            提示：不需要在这里写反讨好规则，下方开关会自动追加全局反讨好块。
          </div>
        </div>

        <div class="form-row-2col">
          <div>
            <label>温度</label>
            <el-input-number v-model="editing.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
            <div class="form-hint-mini">0=确定性 · 2=高随机</div>
          </div>
          <div>
            <label>推荐模型（可空）</label>
            <el-input v-model="editing.modelName" placeholder="如 qwen-plus，留空用全局默认" />
          </div>
        </div>

        <div class="form-row-2col">
          <div class="toggle-row">
            <el-switch v-model="antiSycophancyBool" />
            <div>
              <div class="toggle-label">追加反讨好规则</div>
              <div class="toggle-hint">推荐开启。强制 AI 不附和、不编造、敢于纠正用户</div>
            </div>
          </div>
          <div>
            <label>排序权重（越小越靠前）</label>
            <el-input-number v-model="editing.sortOrder" :min="0" :max="9999" />
          </div>
        </div>

        <!-- 预览 -->
        <div class="preview-block" v-if="previewResult">
          <div class="preview-block-head">
            <span>实际拼装的完整 Prompt（{{ previewResult.promptLength }} 字符）</span>
            <button class="preview-close" @click="previewResult = null">关闭</button>
          </div>
          <pre class="preview-content">{{ previewResult.fullPrompt }}</pre>
        </div>
      </div>

      <template #footer>
        <button class="btn-ghost" @click="dialogVisible = false">取消</button>
        <button class="btn-ghost" @click="handlePreview" :disabled="submitting">
          <el-icon :size="13"><View /></el-icon>
          预览拼装效果
        </button>
        <button class="btn-primary" @click="handleSubmit" :disabled="submitting">
          {{ editing.id ? '保存修改' : '创建' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, StarFilled, CaretRight, Cpu, View } from '@element-plus/icons-vue'
import { personaApi, type Persona, type PreviewResult } from '@/api/persona'

const loading = ref(false)
const personas = ref<Persona[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editing = reactive<Persona>(initPersona())
const previewResult = ref<PreviewResult | null>(null)

const antiSycophancyBool = computed({
  get: () => editing.antiSycophancy === 1,
  set: (v: boolean) => { editing.antiSycophancy = v ? 1 : 0 }
})

function initPersona(): Persona {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    temperature: 0.7,
    modelName: '',
    antiSycophancy: 1,
    enabled: 1,
    sortOrder: 100
  }
}

async function load() {
  loading.value = true
  try {
    const res: any = await personaApi.list()
    personas.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(editing, initPersona())
  previewResult.value = null
  dialogVisible.value = true
}

function openEdit(p: Persona) {
  Object.assign(editing, p)
  // 数据库中 temperature 可能是 string（DECIMAL）→ 转 number
  if (typeof editing.temperature === 'string') {
    editing.temperature = Number(editing.temperature)
  }
  previewResult.value = null
  dialogVisible.value = true
}

async function handlePreview() {
  if (!editing.systemPrompt || !editing.systemPrompt.trim()) {
    ElMessage.warning('先填 system prompt 才能预览')
    return
  }
  try {
    const res: any = await personaApi.preview(editing)
    previewResult.value = res?.data ?? res
  } catch (e: any) {
    ElMessage.error('预览失败：' + (e?.message || ''))
  }
}

async function handleSubmit() {
  if (!editing.name?.trim()) return ElMessage.warning('请填名称')
  if (!editing.systemPrompt?.trim()) return ElMessage.warning('请填 system prompt')

  submitting.value = true
  try {
    if (editing.id) {
      await personaApi.update(editing.id, editing)
      ElMessage.success('已保存')
    } else {
      await personaApi.create(editing)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally {
    submitting.value = false
  }
}

async function setDefault(p: Persona) {
  try {
    await personaApi.setDefault(p.id!)
    ElMessage.success(`已将"${p.name}"设为默认人格`)
    await load()
  } catch (e: any) {
    ElMessage.error('设置失败：' + (e?.message || ''))
  }
}

async function handleDelete(p: Persona) {
  try {
    await ElMessageBox.confirm(`确定删除人格"${p.name}"？此操作不可恢复。`, '提示', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
    await personaApi.delete(p.id!)
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

function truncate(s: string, n: number) {
  if (!s) return ''
  return s.length > n ? s.slice(0, n) + '…' : s
}

onMounted(load)
</script>

<style scoped>
.persona-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 28px 32px 48px;
  background: var(--bg-page);
}

/* ─── 顶部 ─── */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 28px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.page-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.02em;
}
.title-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  background: linear-gradient(135deg, #fce7f3, #fbcfe8);
  color: #be185d;
  letter-spacing: 0.02em;
}
.page-desc {
  font-size: 13.5px;
  color: var(--ink-3);
  max-width: 720px;
  line-height: 1.65;
}

/* ─── 主按钮 ─── */
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  background: linear-gradient(180deg, var(--brand-hover), var(--brand));
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  border-radius: 10px;
  box-shadow: var(--shadow-brand);
  border: none;
  cursor: pointer;
  transition: filter 200ms var(--ease);
}
.btn-primary:hover:not(:disabled) { filter: brightness(1.06); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 8px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 500;
  border-radius: 10px;
  cursor: pointer;
  transition: var(--transition);
}
.btn-ghost:hover { background: var(--bg-hover); }

/* ─── 卡片网格 ─── */
.persona-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 18px;
}
.persona-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px 20px 16px;
  box-shadow: var(--shadow-card);
  transition: all 200ms var(--ease);
}
.persona-card:hover {
  border-color: var(--brand-soft-2);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}
.persona-card.is-default {
  border-color: var(--brand);
  background: linear-gradient(180deg, var(--brand-soft) 0%, var(--bg-surface) 30%);
  box-shadow: 0 0 0 1px var(--brand), var(--shadow-md);
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 8px;
}
.card-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.card-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.01em;
}

.badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  letter-spacing: 0.04em;
}
.badge.default {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand));
  color: #fff;
}
.badge.anti {
  background: linear-gradient(135deg, #fef3c7, #fde68a);
  color: #92400e;
}

.card-actions { display: flex; gap: 4px; }
.icon-btn {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: transparent;
  border: 1px solid transparent;
  color: var(--ink-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: var(--transition);
}
.icon-btn:hover {
  background: var(--bg-hover);
  border-color: var(--line);
  color: var(--ink-1);
}
.icon-btn.danger:hover {
  background: var(--danger-soft);
  border-color: var(--danger);
  color: var(--danger);
}

.card-desc {
  font-size: 13px;
  color: var(--ink-3);
  line-height: 1.6;
  margin-bottom: 12px;
  min-height: 38px;
}

.card-meta {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  font-size: 11.5px;
  color: var(--ink-2);
  font-family: 'JetBrains Mono', monospace;
}

.card-preview {
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}
.preview-label {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--ink-4);
  margin-bottom: 6px;
}
.preview-text {
  font-size: 12.5px;
  color: var(--ink-2);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ─── 空态 ─── */
.empty {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: var(--ink-3);
  gap: 14px;
}
.empty-ring {
  width: 48px;
  height: 48px;
  border: 3px solid var(--line);
  border-top-color: var(--brand);
  border-radius: 50%;
}

/* ─── 表单 ─── */
.form-grid { display: flex; flex-direction: column; gap: 18px; }
.form-row { display: flex; flex-direction: column; gap: 6px; }
.form-row label,
.form-row-2col > div > label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-2);
  letter-spacing: 0.01em;
}
.req { color: var(--danger); }
.form-hint {
  font-size: 11.5px;
  color: var(--ink-3);
  line-height: 1.55;
  margin-top: 2px;
}
.form-hint-mini { font-size: 11px; color: var(--ink-4); margin-top: 3px; }

.form-row-2col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.form-row-2col > div { display: flex; flex-direction: column; gap: 6px; }

.toggle-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--bg-subtle);
  border-radius: 10px;
  border: 1px solid var(--line);
}
.toggle-label { font-size: 13px; font-weight: 600; color: var(--ink-1); }
.toggle-hint { font-size: 11.5px; color: var(--ink-3); margin-top: 2px; }

/* ─── 预览块 ─── */
.preview-block {
  background: #0F1A33;
  color: #DCE3F2;
  border-radius: 10px;
  padding: 12px 14px;
  border: 1px solid #1B2746;
}
.preview-block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 11.5px;
  color: #94A0BD;
  font-family: 'JetBrains Mono', monospace;
}
.preview-close {
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #94A0BD;
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.preview-content {
  margin: 0;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.65;
  max-height: 320px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.preview-content::-webkit-scrollbar { width: 6px; }
.preview-content::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.15); border-radius: 6px; }
</style>

<style>
/* 全局：让 dialog 内的 footer 按钮间距 */
.persona-dialog .el-dialog__footer { padding-top: 12px; }
.persona-dialog .el-dialog__footer > * + * { margin-left: 8px; }
</style>
