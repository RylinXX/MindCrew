<template>
  <div class="ai-config-page">

    <!-- ===== 顶部标题栏 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="title-row">
          <h2 class="page-title">AI 配置中心</h2>
          <el-tag v-if="modifiedCount > 0" type="warning" effect="dark" class="changed-badge">
            {{ modifiedCount }} 项待保存
          </el-tag>
        </div>
        <p class="page-desc">动态调整 RAG 召回、模型参数、缓存策略与安全阈值，配置即时生效无需重启</p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="modifiedCount > 0"
          type="primary"
          :loading="savingAll"
          @click="handleSaveAll"
        >
          保存全部更改
        </el-button>
        <el-button type="danger" plain :loading="resetting" @click="handleResetAll">
          恢复全部默认
        </el-button>
      </div>
    </div>

    <!-- ===== 配置卡片网格 ===== -->
    <div v-loading="loading" class="config-grid">
      <div
        v-for="groupMeta in displayGroups"
        :key="groupMeta.key"
        class="config-card"
        :style="{ '--accent': groupMeta.color }"
      >
        <!-- 卡片头部 -->
        <div class="card-head">
          <div class="card-head-left">
            <span class="card-icon" :style="{ background: groupMeta.lightBg, color: groupMeta.color }">
              {{ groupMeta.icon }}
            </span>
            <div>
              <div class="card-title">{{ groupMeta.label }}</div>
              <div class="card-subtitle">{{ groupMeta.desc }}</div>
            </div>
          </div>
          <el-button text size="small" class="reset-link" @click="handleResetGroup(groupMeta.key)">
            重置默认
          </el-button>
        </div>

        <!-- 配置项列表 -->
        <div class="config-rows">
          <div
            v-for="cfg in grouped[groupMeta.key] || []"
            :key="cfg.configKey"
            class="config-row"
            :class="{ modified: isModified(cfg) }"
          >
            <!-- 标签区 -->
            <div class="row-label">
              <span class="label-text">{{ cfg.label }}</span>
              <el-tooltip v-if="cfg.description" :content="cfg.description" placement="top" :show-after="300">
                <el-icon class="info-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>

            <!-- 控件区 -->
            <div class="row-control">
              <!-- 模型选择 -->
              <template v-if="cfg.configKey === 'llm.model'">
                <el-select v-model="form[cfg.configKey]" style="width: 200px">
                  <el-option v-for="m in models" :key="m" :label="m" :value="m">
                    <span>{{ m }}</span>
                    <el-tag v-if="m === 'qwen-plus'" size="small" type="success" style="margin-left:8px">推荐</el-tag>
                  </el-option>
                </el-select>
              </template>

              <!-- 通用字符串配置 -->
              <template v-else-if="cfg.valueType === 'string'">
                <el-input
                  v-model="form[cfg.configKey]"
                  :type="isLongTextConfig(cfg) ? 'textarea' : 'text'"
                  :rows="isLongTextConfig(cfg) ? 2 : undefined"
                  resize="none"
                  :style="{ width: isLongTextConfig(cfg) ? '280px' : '200px' }"
                />
              </template>

              <!-- 浮点数（附进度条） -->
              <template v-else-if="cfg.valueType === 'float'">
                <div class="float-control">
                  <el-slider
                    v-model="formNum[cfg.configKey]"
                    :min="cfg.minValue ? Number(cfg.minValue) : 0"
                    :max="cfg.maxValue ? Number(cfg.maxValue) : 1"
                    :step="0.05"
                    :show-tooltip="false"
                    class="param-slider"
                  />
                  <el-input-number
                    v-model="formNum[cfg.configKey]"
                    :min="cfg.minValue ? Number(cfg.minValue) : undefined"
                    :max="cfg.maxValue ? Number(cfg.maxValue) : undefined"
                    :step="0.05"
                    :precision="2"
                    controls-position="right"
                    size="small"
                    style="width: 90px"
                  />
                </div>
              </template>

              <!-- 整数 -->
              <template v-else>
                <el-input-number
                  v-model="formNum[cfg.configKey]"
                  :min="cfg.minValue ? Number(cfg.minValue) : undefined"
                  :max="cfg.maxValue ? Number(cfg.maxValue) : undefined"
                  :step="1"
                  controls-position="right"
                  style="width: 130px"
                />
              </template>
            </div>

            <!-- 默认值提示 -->
            <div class="row-default">
              <span class="default-label">默认</span>
              <span class="default-val">{{ cfg.defaultValue }}</span>
              <span v-if="isModified(cfg)" class="modified-dot" :style="{ background: groupMeta.color }"></span>
            </div>
          </div>
        </div>

        <!-- 卡片底部操作 -->
        <div class="card-foot">
          <el-button
            type="primary"
            size="small"
            :loading="saving[groupMeta.key]"
            :style="{ background: groupMeta.color, borderColor: groupMeta.color }"
            @click="handleSaveGroup(groupMeta.key)"
          >
            保存本组
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { aiConfigApi, type AiConfig } from '@/api/aiConfig'

// ===================== 分组元数据 =====================
const groupOrder = [
  {
    key: 'rag',
    label: 'RAG 检索参数',
    desc: '控制召回数量与融合策略',
    icon: '🔍',
    color: '#2563eb',
    lightBg: '#eff6ff'
  },
  {
    key: 'llm',
    label: 'LLM 模型参数',
    desc: '模型选择与生成温度',
    icon: '🤖',
    color: '#7c3aed',
    lightBg: '#f5f3ff'
  },
  {
    key: 'cache',
    label: '缓存策略',
    desc: '高频问题缓存触发与过期',
    icon: '⚡',
    color: '#059669',
    lightBg: '#ecfdf5'
  },
  {
    key: 'safety',
    label: '安全阈值',
    desc: '置信度兜底触发条件',
    icon: '🛡️',
    color: '#d97706',
    lightBg: '#fffbeb'
  }
]

const groupMetaMap = Object.fromEntries(groupOrder.map(group => [group.key, group]))

// ===================== 数据 =====================
const loading = ref(true)
const grouped = ref<Record<string, AiConfig[]>>({})
const models = ref<string[]>([])
const resetting = ref(false)
const savingAll = ref(false)
const saving = reactive<Record<string, boolean>>({})

const form = reactive<Record<string, string>>({})
const formNum = reactive<Record<string, number>>({})

const displayGroups = computed(() => {
  const keys = Object.keys(grouped.value)
  const orderedKeys = [
    ...groupOrder.map(group => group.key).filter(key => keys.includes(key)),
    ...keys.filter(key => !groupMetaMap[key])
  ]

  return orderedKeys.map(key => {
    const fallback = grouped.value[key]?.[0]
    return groupMetaMap[key] || {
      key,
      label: fallback?.groupName || key,
      desc: '接口返回的系统配置分组',
      icon: '⚙️',
      color: '#64748b',
      lightBg: '#f8fafc'
    }
  })
})

// 未保存修改数
const modifiedCount = computed(() => {
  let count = 0
  for (const items of Object.values(grouped.value)) {
    for (const cfg of items) {
      if (isModified(cfg)) count++
    }
  }
  return count
})

// ===================== 初始化 =====================
onMounted(async () => {
  await Promise.all([loadConfigs(), loadModels()])
  loading.value = false
})

const loadConfigs = async () => {
  grouped.value = await aiConfigApi.listAll()
  for (const key of Object.keys(form)) delete form[key]
  for (const key of Object.keys(formNum)) delete formNum[key]
  for (const items of Object.values(grouped.value)) {
    for (const cfg of items) {
      if (cfg.valueType === 'string') {
        form[cfg.configKey] = cfg.configValue
      } else {
        formNum[cfg.configKey] = Number(cfg.configValue)
      }
    }
  }
}

const loadModels = async () => {
  models.value = await aiConfigApi.getModels()
}

// ===================== 工具方法 =====================
const isModified = (cfg: AiConfig) => {
  const current = cfg.valueType === 'string'
    ? form[cfg.configKey]
    : String(formNum[cfg.configKey])
  return current !== cfg.configValue
}

const isLongTextConfig = (cfg: AiConfig) =>
  cfg.configKey.includes('msg') ||
  cfg.label.includes('话术') ||
  cfg.configValue.length > 24 ||
  cfg.defaultValue.length > 24

const collectGroup = (groupKey: string) => {
  const items = grouped.value[groupKey] || []
  const params: Record<string, string> = {}
  for (const cfg of items) {
    params[cfg.configKey] = cfg.valueType === 'string'
      ? form[cfg.configKey] ?? ''
      : String(formNum[cfg.configKey])
  }
  return params
}

const collectAll = () => {
  const params: Record<string, string> = {}
  for (const items of Object.values(grouped.value)) {
    for (const cfg of items) {
      params[cfg.configKey] = cfg.valueType === 'string'
        ? form[cfg.configKey] ?? ''
        : String(formNum[cfg.configKey])
    }
  }
  return params
}

// ===================== 操作 =====================
const handleSaveGroup = async (groupKey: string) => {
  saving[groupKey] = true
  try {
    await aiConfigApi.batchUpdate(collectGroup(groupKey))
    await loadConfigs()
    ElMessage.success('已保存，配置立即生效')
  } finally {
    saving[groupKey] = false
  }
}

const handleSaveAll = async () => {
  savingAll.value = true
  try {
    await aiConfigApi.batchUpdate(collectAll())
    await loadConfigs()
    ElMessage.success('全部配置已保存，立即生效')
  } finally {
    savingAll.value = false
  }
}

const handleResetGroup = async (groupKey: string) => {
  const meta = groupOrder.find(g => g.key === groupKey)!
  await ElMessageBox.confirm(`确认将「${meta.label}」恢复为默认值？`, '提示', { type: 'warning' })
  await aiConfigApi.resetGroup(groupKey)
  await loadConfigs()
  ElMessage.success('已恢复默认值')
}

const handleResetAll = async () => {
  await ElMessageBox.confirm('确认将全部配置恢复为出厂默认值？', '警告', {
    type: 'warning',
    confirmButtonText: '确认重置',
    confirmButtonClass: 'el-button--danger'
  })
  resetting.value = true
  try {
    await aiConfigApi.resetAll()
    await loadConfigs()
    ElMessage.success('全部配置已恢复默认值')
  } finally {
    resetting.value = false
  }
}
</script>

<style scoped>
/* ===== 整体布局 ===== */
.ai-config-page {
  height: 100%;
  overflow-y: auto;
  padding: 24px;
}

/* ===== 顶部标题栏 ===== */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 24px;
  gap: 16px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-1);
  margin: 0;
}

.changed-badge {
  font-size: 12px;
  border-radius: 20px;
}

.page-desc {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  align-items: center;
}

/* ===== 卡片网格 ===== */
.config-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

/* ===== 单张配置卡片 ===== */
.config-card {
  background: var(--bg-surface);
  border-radius: 14px;
  border: 1px solid var(--border);
  border-top: 3px solid var(--accent);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: box-shadow 0.2s ease;
}

.config-card:hover {
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.4);
}

/* ===== 卡片头部 ===== */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border);
}

.card-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-1);
  line-height: 1.4;
}

.card-subtitle {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}

.reset-link {
  color: #94a3b8 !important;
  font-size: 12px;
}
.reset-link:hover {
  color: var(--accent) !important;
}

/* ===== 配置行 ===== */
.config-rows {
  flex: 1;
  padding: 8px 0;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  border-radius: 0;
  transition: background 0.15s;
}

.config-row:hover {
  background: var(--bg-hover);
}

.config-row.modified {
  background: rgba(251, 191, 36, 0.06);
}

/* 标签区 */
.row-label {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 130px;
  flex-shrink: 0;
}

.label-text {
  font-size: 13px;
  color: var(--ink-2);
  font-weight: 500;
}

.info-icon {
  font-size: 13px;
  color: #c4c9d4;
  cursor: default;
  flex-shrink: 0;
}
.info-icon:hover {
  color: #94a3b8;
}

/* 控件区 */
.row-control {
  flex: 1;
  min-width: 0;
}

.float-control {
  display: flex;
  align-items: center;
  gap: 10px;
}

.param-slider {
  flex: 1;
  max-width: 120px;
}

/* 默认值区 */
.row-default {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  min-width: 80px;
}

.default-label {
  font-size: 11px;
  color: var(--ink-3);
}

.default-val {
  display: inline-block;
  max-width: 180px;
  font-size: 12px;
  color: #94a3b8;
  font-family: 'SF Mono', 'Fira Code', monospace;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  padding: 1px 6px;
  border-radius: 4px;
  white-space: normal;
  word-break: break-word;
  line-height: 1.45;
}

.modified-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-left: 2px;
  flex-shrink: 0;
}

/* ===== 卡片底部 ===== */
.card-foot {
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  background: var(--bg-elevated);
}

/* ===== Element Plus 覆盖 ===== */
:deep(.el-input-number .el-input__inner) {
  font-size: 13px;
}

:deep(.el-slider__runway) {
  height: 4px;
}

:deep(.el-slider__button) {
  width: 14px;
  height: 14px;
  border-color: var(--accent, #2563eb);
}

:deep(.el-slider__bar) {
  background: var(--accent, #2563eb);
  height: 4px;
}

:deep(.el-select .el-input__inner) {
  font-size: 13px;
}
</style>
