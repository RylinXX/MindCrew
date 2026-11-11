<template>
  <div class="provider-page">
    <!-- 顶部 -->
    <header class="page-header">
      <div class="page-header-l">
        <div class="title-row">
          <h2 class="page-title">大模型 Provider</h2>
          <span class="title-tag">跨厂商 · 一键切换</span>
        </div>
        <p class="page-desc">
          管理 LLM 厂商配置，全部走 OpenAI 兼容协议（DashScope / DeepSeek / OpenAI / 本地 Ollama / 自建 vLLM）。
          切换激活后立即生效，无需重启。
        </p>
      </div>
      <div class="page-header-r">
        <button class="btn-primary" @click="openCreate">
          <el-icon :size="14"><Plus /></el-icon>
          新建 Provider
        </button>
      </div>
    </header>

    <!-- 卡片网格 -->
    <main class="provider-grid" v-loading="loading">
      <article
        v-for="p in providers"
        :key="p.id"
        class="provider-card"
        :class="{ 'is-active': p.isActive === 1, disabled: p.enabled === 0 }"
      >
        <header class="card-head">
          <div class="card-title-row">
            <h3 class="card-title">{{ p.name }}</h3>
            <span v-if="p.isActive === 1" class="badge active">✓ 激活</span>
            <span v-if="p.enabled === 0" class="badge disabled-badge">禁用</span>
          </div>
          <div class="card-actions">
            <el-tooltip content="编辑" placement="top">
              <button class="icon-btn" @click="openEdit(p)"><el-icon :size="14"><Edit /></el-icon></button>
            </el-tooltip>
            <el-tooltip content="测试连通" placement="top">
              <button class="icon-btn" @click="testOne(p)" :disabled="testing === p.id">
                <el-icon :size="14"><Connection /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip v-if="p.isActive !== 1" content="设为激活" placement="top">
              <button class="icon-btn primary" @click="activateOne(p)">
                <el-icon :size="14"><CircleCheck /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <button class="icon-btn danger" @click="deleteOne(p)" :disabled="p.isActive === 1">
                <el-icon :size="14"><Delete /></el-icon>
              </button>
            </el-tooltip>
          </div>
        </header>

        <p class="card-desc">{{ p.description || '（无描述）' }}</p>

        <div class="card-fields">
          <div class="field">
            <span class="field-label">Endpoint</span>
            <span class="field-value mono">{{ p.baseUrl }}</span>
          </div>
          <div class="field">
            <span class="field-label">Chat Model</span>
            <span class="field-value">{{ p.chatModel || '—' }}</span>
          </div>
          <div class="field" v-if="p.embeddingModel">
            <span class="field-label">Embedding</span>
            <span class="field-value">{{ p.embeddingModel }}<span v-if="p.embeddingDim" class="dim">@{{ p.embeddingDim }}d</span></span>
          </div>
          <div class="field">
            <span class="field-label">API Key</span>
            <span class="field-value mono">
              <template v-if="p.apiKeySet">{{ p.apiKeyMasked }}</template>
              <span v-else class="field-empty">未设置</span>
            </span>
          </div>
          <div class="field">
            <span class="field-label">Temperature</span>
            <span class="field-value mono">{{ Number(p.temperature).toFixed(2) }}</span>
          </div>
        </div>

        <footer class="card-foot" v-if="p.lastTestAt">
          <span class="test-result" :class="{ ok: p.lastTestOk === 1, bad: p.lastTestOk === 0 }">
            <el-icon :size="11">
              <CircleCheck v-if="p.lastTestOk === 1" />
              <CircleClose v-else />
            </el-icon>
            上次测试 · {{ p.lastTestMsg }}
          </span>
        </footer>
      </article>

      <div v-if="!loading && providers.length === 0" class="empty">
        <div class="empty-ring"></div>
        <p>暂无 Provider，点右上角新建</p>
      </div>
    </main>

    <!-- 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing.id ? '编辑：' + editing.name : '新建 Provider'"
      width="720px"
      :close-on-click-modal="false"
      class="provider-dialog"
    >
      <div class="form-grid">
        <div class="form-row-2col">
          <div>
            <label>展示名 <span class="req">*</span></label>
            <el-input v-model="editing.name" placeholder="如：DashScope · 主用" maxlength="50" />
          </div>
          <div>
            <label>协议类型</label>
            <el-input v-model="editing.providerType" placeholder="openai_compatible" disabled />
          </div>
        </div>

        <div class="form-row">
          <label>Base URL <span class="req">*</span></label>
          <el-input v-model="editing.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode" />
          <div class="form-hint-mini">不要带 /v1，系统自动拼接 /v1/chat/completions</div>
        </div>

        <div class="form-row">
          <label>API Key {{ editing.id ? '' : '（首次创建建议填写）' }}</label>
          <el-input
            v-model="editing.apiKey"
            type="password"
            show-password
            :placeholder="editing.id ? '留空表示保留原值，输入空格清除' : '填入厂商提供的 API Key'"
          />
          <div class="form-hint-mini">服务端使用 AES-256-GCM 加密存储，不会明文回显</div>
        </div>

        <div class="form-row-2col">
          <div>
            <label>Chat Model</label>
            <el-input v-model="editing.chatModel" placeholder="qwen-plus / gpt-4o / deepseek-chat ..." />
          </div>
          <div>
            <label>Temperature</label>
            <el-input-number v-model="editing.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
          </div>
        </div>

        <div class="form-row-2col">
          <div>
            <label>Embedding Model（可空）</label>
            <el-input v-model="editing.embeddingModel" placeholder="text-embedding-v3 / bge-m3 ..." />
          </div>
          <div>
            <label>Embedding 维度</label>
            <el-input-number v-model="editing.embeddingDim" :min="0" :max="8192" :step="64" />
          </div>
        </div>

        <div class="form-row">
          <label>备注</label>
          <el-input v-model="editing.description" type="textarea" :rows="2" maxlength="300" />
        </div>

        <div class="form-row-2col">
          <div>
            <label>排序权重</label>
            <el-input-number v-model="editing.sortOrder" :min="0" :max="9999" />
          </div>
          <div class="toggle-row">
            <el-switch v-model="enabledBool" />
            <div>
              <div class="toggle-label">启用</div>
              <div class="toggle-hint">禁用后无法被激活</div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <button class="btn-ghost" @click="dialogVisible = false">取消</button>
        <button class="btn-ghost" @click="handleTest" :disabled="submitting">
          <el-icon :size="13"><Connection /></el-icon>
          {{ testingNow ? '测试中…' : '测试连通' }}
        </button>
        <button class="btn-primary" @click="handleSubmit" :disabled="submitting">
          {{ editing.id ? '保存' : '创建' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, CircleCheck, CircleClose, Connection } from '@element-plus/icons-vue'
import { llmProviderApi, type LlmProvider } from '@/api/llmProvider'

const loading = ref(false)
const testing = ref<number | null>(null)
const testingNow = ref(false)
const providers = ref<LlmProvider[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editing = ref<LlmProvider>(initProvider())

const enabledBool = computed({
  get: () => editing.value.enabled === 1,
  set: v => { editing.value.enabled = v ? 1 : 0 }
})

function initProvider(): LlmProvider {
  return {
    name: '',
    providerType: 'openai_compatible',
    baseUrl: '',
    apiKey: '',
    chatModel: '',
    embeddingModel: '',
    embeddingDim: undefined,
    temperature: 0.7,
    description: '',
    enabled: 1,
    sortOrder: 100,
  }
}

async function load() {
  loading.value = true
  try {
    const res: any = await llmProviderApi.list()
    providers.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally { loading.value = false }
}

function openCreate() {
  editing.value = initProvider()
  dialogVisible.value = true
}

function openEdit(p: LlmProvider) {
  editing.value = { ...initProvider(), ...p, apiKey: '' }   // apiKey 留空 = 保留原值
  if (typeof editing.value.temperature === 'string') editing.value.temperature = Number(editing.value.temperature)
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!editing.value.name?.trim()) return ElMessage.warning('请填展示名')
  if (!editing.value.baseUrl?.trim()) return ElMessage.warning('请填 Base URL')
  submitting.value = true
  try {
    if (editing.value.id) {
      await llmProviderApi.update(editing.value.id, editing.value)
      ElMessage.success('已保存')
    } else {
      await llmProviderApi.create(editing.value)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { submitting.value = false }
}

async function handleTest() {
  testingNow.value = true
  try {
    let res: any
    if (editing.value.id) {
      // 已保存的 provider；如果填了新 apiKey 用它覆盖测试
      res = await llmProviderApi.testById(editing.value.id, editing.value.apiKey || undefined)
    } else {
      // 未保存的临时测试
      if (!editing.value.baseUrl || !editing.value.chatModel) {
        return ElMessage.warning('需要 Base URL + Chat Model 才能测试')
      }
      res = await llmProviderApi.testRaw(editing.value)
    }
    const r = res?.data ?? res
    if (r.success) ElMessage.success('连通正常 · ' + r.message)
    else ElMessage.error('测试失败 · ' + r.message)
  } catch (e: any) {
    ElMessage.error('测试异常：' + (e?.message || ''))
  } finally { testingNow.value = false }
}

async function testOne(p: LlmProvider) {
  testing.value = p.id!
  try {
    const res: any = await llmProviderApi.testById(p.id!)
    const r = res?.data ?? res
    if (r.success) ElMessage.success(p.name + ' · ' + r.message)
    else ElMessage.error(p.name + ' · ' + r.message)
    await load()
  } catch (e: any) {
    ElMessage.error('测试异常：' + (e?.message || ''))
  } finally { testing.value = null }
}

async function activateOne(p: LlmProvider) {
  try {
    await ElMessageBox.confirm(
      `确定切换到 "${p.name}"？\nChat 和 Embedding 调用将立即使用新配置（无需重启）。`,
      '切换激活 Provider', { type: 'warning' }
    )
    await llmProviderApi.setActive(p.id!)
    ElMessage.success(`已切换激活 Provider 为 "${p.name}"`)
    await load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('切换失败：' + (e?.message || ''))
  }
}

async function deleteOne(p: LlmProvider) {
  if (p.isActive === 1) return ElMessage.warning('不能删除激活的 Provider')
  try {
    await ElMessageBox.confirm(`确定删除 "${p.name}"？`, '提示', { type: 'warning' })
    await llmProviderApi.delete(p.id!)
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

onMounted(load)
</script>

<style scoped>
.provider-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 28px 32px 48px;
  background: var(--bg-page);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 28px;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
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
  background: linear-gradient(135deg, #e0e7ff, #c7d2fe);
  color: #4338ca;
}
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }

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
  transition: filter 200ms;
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
}
.btn-ghost:hover { background: var(--bg-hover); }

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 18px;
}
.provider-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px 20px 14px;
  box-shadow: var(--shadow-card);
  transition: all 200ms;
}
.provider-card:hover {
  border-color: var(--brand-soft-2);
  box-shadow: var(--shadow-md);
}
.provider-card.is-active {
  border-color: var(--brand);
  background: linear-gradient(180deg, var(--brand-soft) 0%, var(--bg-surface) 30%);
  box-shadow: 0 0 0 1px var(--brand), var(--shadow-md);
}
.provider-card.disabled { opacity: 0.55; }

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 6px;
}
.card-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.card-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-1);
}
.badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}
.badge.active {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand));
  color: #fff;
}
.badge.disabled-badge {
  background: var(--bg-subtle);
  color: var(--ink-3);
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
  transition: all 180ms;
}
.icon-btn:hover { background: var(--bg-hover); border-color: var(--line); color: var(--ink-1); }
.icon-btn.primary:hover {
  background: var(--brand-soft);
  border-color: var(--brand);
  color: var(--brand);
}
.icon-btn.danger:hover { background: var(--danger-soft); border-color: var(--danger); color: var(--danger); }
.icon-btn:disabled { opacity: 0.35; cursor: not-allowed; }

.card-desc { font-size: 13px; color: var(--ink-3); line-height: 1.6; margin-bottom: 12px; min-height: 36px; }

.card-fields { display: flex; flex-direction: column; gap: 6px; }
.field {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}
.field-label {
  flex-shrink: 0;
  width: 80px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-4);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.field-value { color: var(--ink-1); word-break: break-all; }
.field-value.mono { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.field-empty { color: var(--ink-4); font-style: italic; }
.dim { color: var(--ink-3); font-size: 10.5px; margin-left: 4px; font-family: 'JetBrains Mono', monospace; }

.card-foot {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}
.test-result {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  font-family: 'JetBrains Mono', monospace;
  color: var(--ink-3);
}
.test-result.ok { color: var(--success-ink); }
.test-result.bad { color: var(--danger); }

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

.form-grid { display: flex; flex-direction: column; gap: 16px; }
.form-row { display: flex; flex-direction: column; gap: 6px; }
.form-row label, .form-row-2col > div > label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-2);
}
.req { color: var(--danger); }
.form-hint-mini { font-size: 11px; color: var(--ink-4); margin-top: 2px; }
.form-row-2col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.form-row-2col > div { display: flex; flex-direction: column; gap: 6px; }
.toggle-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg-subtle);
  border-radius: 10px;
  border: 1px solid var(--line);
}
.toggle-label { font-size: 13px; font-weight: 600; color: var(--ink-1); }
.toggle-hint { font-size: 11px; color: var(--ink-3); }
</style>

<style>
.provider-dialog .el-dialog__footer { padding-top: 12px; }
.provider-dialog .el-dialog__footer > * + * { margin-left: 8px; }
</style>
