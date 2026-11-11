<template>
  <div class="pii-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">PII 数据脱敏</h2>
          <span class="title-tag">任务 12 · 合规防护</span>
        </div>
        <p class="page-desc">
          自动识别用户输入和文档中的 <strong>手机号 / 身份证 / 银行卡 / 邮箱</strong>，
          按配置在响应或入库前打码。三类场景独立开关，可热改无需重启。
        </p>
      </div>
    </header>

    <div class="pii-grid" v-loading="loading">
      <!-- 总开关 -->
      <section class="pii-card">
        <div class="card-head">
          <h3>全局开关</h3>
          <el-switch v-model="enabledBool" inline-prompt active-text="启用" inactive-text="关闭" />
        </div>
        <p class="card-desc">关闭后所有 PII 规则失效（仅紧急情况使用）。</p>
      </section>

      <!-- 检测规则 -->
      <section class="pii-card">
        <div class="card-head"><h3>检测规则</h3></div>
        <div class="rule-list">
          <label class="rule-row">
            <el-switch v-model="maskPhoneBool" />
            <div><div class="rule-name">手机号</div><div class="rule-eg">138****1234</div></div>
          </label>
          <label class="rule-row">
            <el-switch v-model="maskIdCardBool" />
            <div><div class="rule-name">身份证号</div><div class="rule-eg">110101********1234</div></div>
          </label>
          <label class="rule-row">
            <el-switch v-model="maskBankCardBool" />
            <div><div class="rule-name">银行卡号</div><div class="rule-eg">6217******1234</div></div>
          </label>
          <label class="rule-row">
            <el-switch v-model="maskEmailBool" />
            <div><div class="rule-name">邮箱</div><div class="rule-eg">j***@gmail.com</div></div>
          </label>
        </div>
      </section>

      <!-- 应用场景 -->
      <section class="pii-card">
        <div class="card-head"><h3>应用场景</h3></div>
        <div class="rule-list">
          <label class="rule-row scope">
            <el-switch v-model="onResponseBool" />
            <div>
              <div class="rule-name">问答响应</div>
              <div class="rule-eg">用户问答时出口脱敏 · 不动数据库（推荐开）</div>
            </div>
          </label>
          <label class="rule-row scope">
            <el-switch v-model="onAuditBool" />
            <div>
              <div class="rule-name">审计日志</div>
              <div class="rule-eg">写 detail_json 前脱敏（推荐开）</div>
            </div>
          </label>
          <label class="rule-row scope warn">
            <el-switch v-model="onUploadBool" />
            <div>
              <div class="rule-name">文档入库 <span class="dangerous">不可逆</span></div>
              <div class="rule-eg">上传时直接打码后入库 · 原始数据不保留 · 默认关</div>
            </div>
          </label>
        </div>
      </section>

      <!-- 测试 -->
      <section class="pii-card span-2">
        <div class="card-head"><h3>实时测试</h3></div>
        <el-input
          v-model="testInput"
          type="textarea"
          :rows="3"
          placeholder="输入包含 PII 的文本，看脱敏效果。如：我的手机号是 13812345678，身份证 11010119900101001X"
        />
        <div class="test-actions">
          <el-button type="primary" @click="runTest" :disabled="!testInput.trim()">查看脱敏结果</el-button>
        </div>
        <div v-if="testOutput" class="test-result">
          <div class="dk">脱敏后：</div>
          <pre>{{ testOutput }}</pre>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { piiApi, type PiiConfig } from '@/api/audit'

const loading = ref(false)
const config = ref<PiiConfig>({})
let suppressWatcher = false

const enabledBool      = computed({ get: () => config.value.enabled === 1,         set: v => updateField('enabled', v) })
const maskPhoneBool    = computed({ get: () => config.value.maskPhone === 1,       set: v => updateField('maskPhone', v) })
const maskIdCardBool   = computed({ get: () => config.value.maskIdCard === 1,      set: v => updateField('maskIdCard', v) })
const maskBankCardBool = computed({ get: () => config.value.maskBankCard === 1,    set: v => updateField('maskBankCard', v) })
const maskEmailBool    = computed({ get: () => config.value.maskEmail === 1,       set: v => updateField('maskEmail', v) })
const onUploadBool     = computed({ get: () => config.value.applyOnUpload === 1,   set: v => updateField('applyOnUpload', v) })
const onResponseBool   = computed({ get: () => config.value.applyOnResponse === 1, set: v => updateField('applyOnResponse', v) })
const onAuditBool      = computed({ get: () => config.value.applyOnAudit === 1,    set: v => updateField('applyOnAudit', v) })

const updateField = (key: keyof PiiConfig, v: boolean) => {
  if (suppressWatcher) return
  (config.value as any)[key] = v ? 1 : 0
  persist()
}

const load = async () => {
  loading.value = true
  try {
    const res: any = await piiApi.getConfig()
    suppressWatcher = true
    config.value = res?.data ?? res ?? {}
  } catch (e: any) {
    ElMessage.error('加载配置失败：' + (e?.message || ''))
  } finally {
    suppressWatcher = false
    loading.value = false
  }
}

const persist = async () => {
  try {
    await piiApi.updateConfig(config.value)
    ElMessage.success('已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
    load()    // 回滚到服务端真实值
  }
}

const testInput = ref('我的手机号是 13812345678，身份证 11010119900101001X，银行卡 6217001234567890123，邮箱 john@example.com')
const testOutput = ref('')

const runTest = async () => {
  try {
    const res: any = await piiApi.test(testInput.value)
    testOutput.value = (res?.data ?? res)?.output || ''
  } catch (e: any) {
    ElMessage.error('测试失败：' + (e?.message || ''))
  }
}

onMounted(load)
</script>

<style scoped>
.pii-page { padding: 24px 28px 40px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { margin-bottom: 20px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.page-title { font-size: 22px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #fee2e2, #fecaca); color: #b91c1c;
}
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 760px; line-height: 1.55; }

.pii-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
.pii-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 18px 20px;
}
.pii-card.span-2 { grid-column: 1 / -1; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.card-head h3 { font-size: 14.5px; font-weight: 700; color: var(--ink-1); }
.card-desc { font-size: 12px; color: var(--ink-3); margin-top: 6px; }

.rule-list { display: flex; flex-direction: column; gap: 10px; }
.rule-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px;
  background: var(--bg-elevated);
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
}
.rule-row.scope { border-color: rgba(56,189,248,0.2); background: rgba(56,189,248,0.04); }
.rule-row.warn { border-color: rgba(245,158,11,0.3); background: rgba(245,158,11,0.05); }
.rule-name { font-size: 13px; font-weight: 600; color: var(--ink-1); }
.rule-eg { font-size: 11.5px; color: var(--ink-3); margin-top: 2px; font-family: 'JetBrains Mono', monospace; }
.dangerous {
  font-size: 10px; padding: 1px 6px; border-radius: 4px;
  background: rgba(248,113,113,0.15); color: #b91c1c; font-weight: 700;
  margin-left: 6px;
}

.test-actions { margin: 12px 0; }
.test-result {
  background: rgba(52,211,153,0.05);
  border: 1px solid rgba(52,211,153,0.2);
  border-radius: 8px;
  padding: 12px 14px;
}
.test-result .dk { font-size: 11.5px; color: var(--ink-3); margin-bottom: 6px; font-weight: 600; }
.test-result pre {
  margin: 0; font-family: 'JetBrains Mono', monospace;
  font-size: 13px; color: #047857; white-space: pre-wrap; word-break: break-all;
}

@media (max-width: 900px) {
  .pii-grid { grid-template-columns: 1fr; }
  .pii-card.span-2 { grid-column: 1; }
}
</style>
