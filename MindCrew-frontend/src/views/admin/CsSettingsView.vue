<template>
  <div class="cs-settings-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">客服中心配置</h2>
          <span class="title-tag">对外页面 · SSO · 钉钉渠道</span>
        </div>
        <p class="page-desc">
          配置 /cs 对外客服页的标题、欢迎语、主题色与快捷问题；接入客户自有系统的 SSO 单点登录；
          配置钉钉机器人渠道。保存后对访客立即生效。
        </p>
      </div>
      <div class="header-stats" v-if="overview">
        <div class="head-stat"><strong>{{ overview.ssoAppCount }}</strong><span>SSO 应用</span></div>
        <div class="head-stat"><strong>{{ overview.sessionTotal }}</strong><span>累计会话</span></div>
        <div class="head-stat"><strong>{{ overview.todaySessions }}</strong><span>今日会话</span></div>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="cs-tabs">
      <!-- ───────────── 页面配置 ───────────── -->
      <el-tab-pane label="页面配置" name="page">
        <div class="page-config-grid">
          <section class="panel">
            <div class="panel-head">
              <h3>对外页面</h3>
              <el-button type="primary" :loading="savingConfig" @click="saveConfig">保存配置</el-button>
            </div>

            <el-form label-position="top" class="config-form" v-loading="configLoading">
              <div class="form-grid-2">
                <el-form-item label="页面标题">
                  <el-input v-model="config.pageTitle" maxlength="100" placeholder="MindCrew 智能客服" />
                </el-form-item>
                <el-form-item label="副标题">
                  <el-input v-model="config.pageSubtitle" maxlength="200" placeholder="7×24 小时智能问答" />
                </el-form-item>
              </div>

              <el-form-item label="欢迎语">
                <el-input v-model="config.welcomeText" type="textarea" :rows="2" maxlength="1000" placeholder="首屏欢迎语，留空使用默认话术" />
              </el-form-item>

              <div class="form-grid-2">
                <el-form-item label="AI 坐席名">
                  <el-input v-model="config.greetingAgentName" maxlength="50" placeholder="小梦" />
                </el-form-item>
                <el-form-item label="Logo URL">
                  <el-input v-model="config.logoUrl" maxlength="500" placeholder="https://.../logo.png" />
                </el-form-item>
              </div>

              <div class="form-grid-2">
                <el-form-item label="主题色">
                  <div class="color-row">
                    <el-color-picker v-model="config.themeColor" />
                    <el-input v-model="config.themeColor" maxlength="20" placeholder="#3D5AFE" />
                  </div>
                </el-form-item>
                <el-form-item label="工作时间">
                  <el-input v-model="config.workHours" maxlength="100" placeholder="9:00-18:00" />
                </el-form-item>
              </div>

              <el-form-item label="快捷问题（回车添加）">
                <el-select
                  v-model="quickQuestions"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  :reserve-keyword="false"
                  placeholder="如：怎么配置权限？"
                  style="width: 100%"
                />
              </el-form-item>

              <el-form-item label="转人工关键词（命中即转人工）">
                <el-select
                  v-model="handoffKeywords"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  :reserve-keyword="false"
                  placeholder="如：转人工、人工客服"
                  style="width: 100%"
                />
              </el-form-item>

              <el-form-item label="离线提示（非工作时间展示）">
                <el-input v-model="config.offlineTip" type="textarea" :rows="2" maxlength="500" placeholder="当前非工作时间，请留言或明天再来" />
              </el-form-item>

              <el-form-item label="AI 检索范围（知识库，留空=全部可见）">
                <el-select v-model="kbIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="默认全部可见" style="width: 100%">
                  <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </el-form-item>

              <div class="sub-section-title">SSO 单点登录</div>
              <div class="switch-grid">
                <div class="switch-item">
                  <div>
                    <div class="switch-label">开放 SSO 单点登录</div>
                    <div class="switch-hint">关闭后 /api/cs/sso/entry 一律 302 回 cs_error</div>
                  </div>
                  <el-switch v-model="config.ssoEnabled" :active-value="1" :inactive-value="0" />
                </div>
              </div>
              <el-form-item v-if="config.ssoEnabled === 1" label="跳转模式" class="sso-mode-item">
                <el-radio-group v-model="config.ssoMode">
                  <el-radio value="internal">internal · 跳转本系统 /cs</el-radio>
                  <el-radio value="external">external · 跳转客户客服页</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item
                v-if="config.ssoEnabled === 1 && config.ssoMode === 'external'"
                label="客户客服页地址"
              >
                <el-input v-model="config.externalCsUrl" maxlength="500" placeholder="https://customer.example.com/cs" />
              </el-form-item>

              <div class="sub-section-title">页面能力</div>
              <div class="switch-grid no-border">
                <div class="switch-item">
                  <div>
                    <div class="switch-label">AI 先答</div>
                    <div class="switch-hint">关闭后访客消息直接转人工</div>
                  </div>
                  <el-switch v-model="config.aiEnabled" :active-value="1" :inactive-value="0" />
                </div>
                <div class="switch-item">
                  <div>
                    <div class="switch-label">满意度评价</div>
                    <div class="switch-hint">会话结束后邀请访客评分</div>
                  </div>
                  <el-switch v-model="config.satisfactionEnabled" :active-value="1" :inactive-value="0" />
                </div>
                <div class="switch-item">
                  <div>
                    <div class="switch-label">显示 MindCrew 品牌</div>
                    <div class="switch-hint">页脚展示 Powered by MindCrew</div>
                  </div>
                  <el-switch v-model="config.showBrand" :active-value="1" :inactive-value="0" />
                </div>
              </div>
            </el-form>
          </section>

          <!-- 实时预览 -->
          <aside class="preview-wrap">
            <div class="preview-label">实时预览</div>
            <div class="phone" :style="{ '--phone-theme': config.themeColor || '#3D5AFE' }">
              <div class="phone-notch"></div>
              <header class="phone-head">
                <div class="phone-logo">
                  <img v-if="config.logoUrl" :src="config.logoUrl" alt="" />
                  <span v-else>{{ (config.pageTitle || 'M').slice(0, 1) }}</span>
                </div>
                <div class="phone-titles">
                  <div class="phone-title">{{ config.pageTitle || 'MindCrew 智能客服' }}</div>
                  <div class="phone-sub">{{ config.pageSubtitle || '7×24 小时智能问答' }}</div>
                </div>
              </header>
              <div class="phone-body">
                <div class="phone-bubble">
                  {{ config.welcomeText || ('你好，我是' + (config.greetingAgentName || '智能客服') + '，有什么可以帮你？') }}
                </div>
                <div class="phone-chips">
                  <span v-for="q in quickQuestions.slice(0, 4)" :key="q" class="phone-chip">{{ q }}</span>
                </div>
                <div class="phone-hours" v-if="config.workHours">服务时间：{{ config.workHours }}</div>
                <div class="phone-hours offline" v-if="config.offlineTip">
                  {{ config.offlineTip }}
                </div>
              </div>
              <div class="phone-input">输入你的问题…</div>
              <div class="phone-brand" v-if="config.showBrand === 1">Powered by MindCrew</div>
            </div>
          </aside>
        </div>
      </el-tab-pane>

      <!-- ───────────── SSO ───────────── -->
      <el-tab-pane label="SSO 对接" name="sso">
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>单点登录应用</h3>
              <p class="panel-sub">客户系统签名后 302 跳转 /cs，前端用一次性 ticket 换本地登录态。</p>
            </div>
            <el-button type="primary" :icon="Plus" @click="openCreateApp">新建 SSO 应用</el-button>
          </div>

          <el-table :data="ssoApps" v-loading="ssoLoading" style="width: 100%">
            <el-table-column prop="appName" label="应用名称" min-width="140" />
            <el-table-column label="App ID" min-width="180">
              <template #default="{ row }">
                <span class="mono">{{ row.appId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="默认角色" width="100">
              <template #default="{ row }">{{ row.defaultRole || 'user' }}</template>
            </el-table-column>
            <el-table-column label="自动建号" width="100">
              <template #default="{ row }">
                <span :class="row.autoCreateUser === 1 ? 'text-ok' : 'text-muted'">
                  {{ row.autoCreateUser === 1 ? '是' : '否' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="90">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled === 1"
                  @change="(val: any) => toggleSsoApp(row, Boolean(val))"
                />
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEditApp(row)">编辑</el-button>
                <el-button link type="warning" size="small" @click="rotateSecret(row)">重置密钥</el-button>
                <el-button link type="danger" size="small" @click="removeApp(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="sign-tip">
            <h4>签名协议</h4>
            <p>
              签名：<code>sign = hex(HMAC_SHA256(appSecret, appId + "\n" + externalUserId + "\n" + ts + "\n" + nonce))</code>，
              ts 为秒级时间戳（±300s），nonce 防重放；入口
              <code>GET /api/cs/sso/entry?appId&userId&name&ts&nonce&sign&redirect=/cs</code>。
              未开启 SSO 或签名失败会 302 回 <code>/cs?cs_error=invalid_sign</code>。
            </p>
          </div>
        </section>
      </el-tab-pane>

      <!-- ───────────── 钉钉 ───────────── -->
      <el-tab-pane label="钉钉渠道" name="dingtalk">
        <div class="dingtalk-grid">
          <section class="panel">
            <div class="panel-head">
              <h3>钉钉机器人</h3>
              <el-button type="primary" :loading="savingDingTalk" @click="saveDingTalk">保存配置</el-button>
            </div>

            <el-form label-position="top" v-loading="dingTalkLoading">
              <div class="form-grid-2">
                <el-form-item label="渠道名称">
                  <el-input v-model="dingTalk.name" maxlength="100" placeholder="钉钉客服机器人" />
                </el-form-item>
                <el-form-item label="启用">
                  <el-switch v-model="dingTalk.enabled" :active-value="1" :inactive-value="0" />
                </el-form-item>
              </div>
              <div class="form-grid-2">
                <el-form-item label="AppKey">
                  <el-input v-model="dingTalk.appKey" maxlength="100" placeholder="钉钉应用 AppKey" />
                </el-form-item>
                <el-form-item label="Robot Code">
                  <el-input v-model="dingTalk.robotCode" maxlength="100" placeholder="机器人编码" />
                </el-form-item>
              </div>
              <el-form-item label="AppSecret（留空或保留打码占位则不修改）">
                <el-input v-model="dingTalk.appSecret" type="password" show-password placeholder="已保存的密钥会打码显示" />
              </el-form-item>
              <el-form-item label="回调验签密钥 SignSecret（留空不修改）">
                <el-input v-model="dingTalk.signSecret" type="password" show-password placeholder="未配置签名可留空" />
              </el-form-item>
              <el-form-item label="Webhook URL">
                <el-input v-model="dingTalk.webhookUrl" maxlength="500" placeholder="https://oapi.dingtalk.com/robot/send?access_token=..." />
              </el-form-item>
              <el-form-item label="AI 检索范围（知识库，留空沿用客服中心配置）">
                <el-select v-model="dingTalkKbIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="默认沿用客服中心配置" style="width: 100%">
                  <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="欢迎语">
                <el-input v-model="dingTalk.welcomeText" type="textarea" :rows="2" maxlength="500" placeholder="渠道欢迎语" />
              </el-form-item>
            </el-form>
          </section>

          <section class="panel">
            <div class="panel-head">
              <h3>测试回答</h3>
            </div>
            <p class="panel-sub">调用同一套 RAG 链路生成回复，只返回文本、不会真的发送到钉钉。</p>
            <el-input
              v-model="testContent"
              type="textarea"
              :rows="4"
              maxlength="500"
              placeholder="输入一条访客问题，例如：怎么配置知识库权限？"
              class="test-input"
            />
            <div class="test-actions">
              <el-button type="primary" :loading="testingDingTalk" :disabled="!testContent.trim()" @click="testDingTalk">
                测试回答
              </el-button>
              <span v-if="testElapsed" class="test-elapsed">耗时 {{ (testElapsed / 1000).toFixed(1) }}s</span>
            </div>
            <div class="test-result" v-if="testResult">
              <div class="test-result-label">AI 回答</div>
              <div class="test-result-body">{{ testResult }}</div>
            </div>
            <div class="test-empty" v-else>
              <el-icon :size="26"><ChatDotRound /></el-icon>
              <p>测试结果会显示在这里</p>
            </div>
          </section>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- SSO 应用编辑弹窗 -->
    <el-dialog v-model="appDialogVisible" :title="editingAppId ? '编辑 SSO 应用' : '新建 SSO 应用'" width="520px">
      <el-form label-position="top">
        <el-form-item label="应用名称" required>
          <el-input v-model="appForm.appName" maxlength="100" placeholder="如：客户官网" />
        </el-form-item>
        <div class="form-grid-2">
          <el-form-item label="默认角色">
            <el-select v-model="appForm.defaultRole" style="width: 100%">
              <el-option label="普通用户 user" value="user" />
              <el-option label="审计员 auditor" value="auditor" />
              <el-option label="管理员 admin" value="admin" />
            </el-select>
          </el-form-item>
          <el-form-item label="首次登录自动建号">
            <el-switch v-model="appForm.autoCreateUser" :active-value="1" :inactive-value="0" />
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="appForm.remark" type="textarea" :rows="2" maxlength="500" placeholder="对接用途说明" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="appForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingApp" @click="submitApp">保存</el-button>
      </template>
    </el-dialog>

    <!-- 一次性明文密钥 -->
    <el-dialog v-model="secretVisible" title="请立即保存密钥" width="560px" :close-on-click-modal="false">
      <div class="secret-body">
        <p class="secret-warn">
          密钥仅显示这一次，关闭后无法再次查看。请复制并妥善保存到客户系统的配置中。
        </p>
        <div class="secret-row" v-if="secretAppId">
          <span class="secret-label">App ID</span>
          <code class="secret-value">{{ secretAppId }}</code>
          <el-button size="small" @click="copyText(secretAppId)">复制</el-button>
        </div>
        <div class="secret-row">
          <span class="secret-label">App Secret</span>
          <code class="secret-value">{{ secretValue }}</code>
          <el-button size="small" @click="copyText(secretValue)">复制</el-button>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="secretVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Plus } from '@element-plus/icons-vue'
import {
  csAdminApi,
  type CsConfig,
  type CsDingTalkChannel,
  type CsOverview,
  type CsSsoApp
} from '@/api/cs'
import { knowledgeApi } from '@/api/knowledge'

const activeTab = ref('page')
const overview = ref<CsOverview | null>(null)

const knowledgeBases = ref<Array<{ id: number; name: string }>>([])

// ─────────────────────────────────────────────
// 页面配置
// ─────────────────────────────────────────────
interface ConfigForm {
  pageTitle: string
  pageSubtitle: string
  welcomeText: string
  logoUrl: string
  themeColor: string
  workHours: string
  offlineTip: string
  greetingAgentName: string
  aiEnabled: number
  satisfactionEnabled: number
  showBrand: number
  ssoEnabled: number
  ssoMode: string
  externalCsUrl: string
}

const config = ref<ConfigForm>({
  pageTitle: '',
  pageSubtitle: '',
  welcomeText: '',
  logoUrl: '',
  themeColor: '#3D5AFE',
  workHours: '',
  offlineTip: '',
  greetingAgentName: '',
  aiEnabled: 1,
  satisfactionEnabled: 1,
  showBrand: 1,
  ssoEnabled: 0,
  ssoMode: 'internal',
  externalCsUrl: ''
})

const quickQuestions = ref<string[]>([])
const handoffKeywords = ref<string[]>([])
const kbIds = ref<number[]>([])

const configLoading = ref(false)
const savingConfig = ref(false)

function parseJsonArray<T>(raw?: string | null): T[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as T[]) : []
  } catch {
    return []
  }
}

function applyConfig(data: CsConfig) {
  config.value.pageTitle = data.pageTitle || ''
  config.value.pageSubtitle = data.pageSubtitle || ''
  config.value.welcomeText = data.welcomeText || ''
  config.value.logoUrl = data.logoUrl || ''
  config.value.themeColor = data.themeColor || '#3D5AFE'
  config.value.workHours = data.workHours || ''
  config.value.offlineTip = data.offlineTip || ''
  config.value.greetingAgentName = data.greetingAgentName || ''
  config.value.aiEnabled = data.aiEnabled ?? 1
  config.value.satisfactionEnabled = data.satisfactionEnabled ?? 1
  config.value.showBrand = data.showBrand ?? 1
  config.value.ssoEnabled = data.ssoEnabled ?? 0
  config.value.ssoMode = data.ssoMode || 'internal'
  config.value.externalCsUrl = data.externalCsUrl || ''
  quickQuestions.value = parseJsonArray<string>(data.quickQuestions)
  handoffKeywords.value = parseJsonArray<string>(data.handoffKeywords)
  kbIds.value = parseJsonArray<number>(data.kbIds)
}

async function loadConfig() {
  configLoading.value = true
  try {
    const data = await csAdminApi.getConfig()
    applyConfig(data)
  } catch (e: any) {
    ElMessage.error('加载客服配置失败：' + (e?.message || ''))
  } finally {
    configLoading.value = false
  }
}

async function saveConfig() {
  savingConfig.value = true
  try {
    // 数组字段按后端契约序列化为 JSON 字符串；清空时用空数组字符串
    const payload: CsConfig = {
      pageTitle: config.value.pageTitle,
      pageSubtitle: config.value.pageSubtitle,
      welcomeText: config.value.welcomeText,
      logoUrl: config.value.logoUrl,
      themeColor: config.value.themeColor,
      quickQuestions: JSON.stringify(quickQuestions.value),
      workHours: config.value.workHours,
      offlineTip: config.value.offlineTip,
      aiEnabled: config.value.aiEnabled,
      handoffKeywords: JSON.stringify(handoffKeywords.value),
      kbIds: JSON.stringify(kbIds.value),
      ssoEnabled: config.value.ssoEnabled,
      ssoMode: config.value.ssoMode,
      externalCsUrl: config.value.externalCsUrl,
      satisfactionEnabled: config.value.satisfactionEnabled,
      showBrand: config.value.showBrand,
      greetingAgentName: config.value.greetingAgentName
    }
    const saved = await csAdminApi.saveConfig(payload)
    applyConfig(saved)
    ElMessage.success('客服中心配置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally {
    savingConfig.value = false
  }
}

// ─────────────────────────────────────────────
// SSO
// ─────────────────────────────────────────────
const ssoApps = ref<CsSsoApp[]>([])
const ssoLoading = ref(false)
const appDialogVisible = ref(false)
const editingAppId = ref<number | null>(null)
const savingApp = ref(false)

const appForm = ref({
  appName: '',
  defaultRole: 'user',
  autoCreateUser: 1,
  enabled: 1,
  remark: ''
})

const secretVisible = ref(false)
const secretValue = ref('')
const secretAppId = ref('')

async function loadSsoApps() {
  ssoLoading.value = true
  try {
    ssoApps.value = await csAdminApi.listSsoApps()
  } catch (e: any) {
    ElMessage.error('加载 SSO 应用失败：' + (e?.message || ''))
  } finally {
    ssoLoading.value = false
  }
}

function openCreateApp() {
  editingAppId.value = null
  appForm.value.appName = ''
  appForm.value.defaultRole = 'user'
  appForm.value.autoCreateUser = 1
  appForm.value.enabled = 1
  appForm.value.remark = ''
  appDialogVisible.value = true
}

function openEditApp(row: CsSsoApp) {
  editingAppId.value = row.id ?? null
  appForm.value.appName = row.appName || ''
  appForm.value.defaultRole = row.defaultRole || 'user'
  appForm.value.autoCreateUser = row.autoCreateUser ?? 1
  appForm.value.enabled = row.enabled ?? 1
  appForm.value.remark = row.remark || ''
  appDialogVisible.value = true
}

async function submitApp() {
  if (!appForm.value.appName.trim()) {
    ElMessage.warning('请填写应用名称')
    return
  }
  savingApp.value = true
  try {
    if (editingAppId.value) {
      await csAdminApi.updateSsoApp(editingAppId.value, {
        appName: appForm.value.appName.trim(),
        defaultRole: appForm.value.defaultRole,
        autoCreateUser: appForm.value.autoCreateUser,
        enabled: appForm.value.enabled,
        remark: appForm.value.remark
      })
      ElMessage.success('SSO 应用已更新')
    } else {
      const created = await csAdminApi.createSsoApp({
        appName: appForm.value.appName.trim(),
        defaultRole: appForm.value.defaultRole,
        autoCreateUser: appForm.value.autoCreateUser,
        enabled: appForm.value.enabled,
        remark: appForm.value.remark
      })
      secretAppId.value = created.app?.appId || ''
      secretValue.value = created.appSecret
      secretVisible.value = true
      ElMessage.success('SSO 应用已创建')
    }
    appDialogVisible.value = false
    await loadSsoApps()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally {
    savingApp.value = false
  }
}

async function toggleSsoApp(row: CsSsoApp, enabled: boolean) {
  if (!row.id) return
  try {
    await csAdminApi.updateSsoApp(row.id, { enabled: enabled ? 1 : 0 })
    row.enabled = enabled ? 1 : 0
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch (e: any) {
    ElMessage.error('更新失败：' + (e?.message || ''))
  }
}

async function rotateSecret(row: CsSsoApp) {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(
      `重置「${row.appName}」的密钥后，旧密钥立即失效，客户系统需同步更新。确认继续？`,
      '重置密钥',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const result = await csAdminApi.rotateSecret(row.id)
    secretAppId.value = result.appId
    secretValue.value = result.appSecret
    secretVisible.value = true
  } catch (e: any) {
    ElMessage.error('重置失败：' + (e?.message || ''))
  }
}

async function removeApp(row: CsSsoApp) {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认删除 SSO 应用「${row.appName}」？`, '删除应用', { type: 'warning' })
  } catch {
    return
  }
  try {
    await csAdminApi.deleteSsoApp(row.id)
    ElMessage.success('已删除')
    await loadSsoApps()
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

// ─────────────────────────────────────────────
// 钉钉
// ─────────────────────────────────────────────
const dingTalk = ref<CsDingTalkChannel>({
  name: '',
  appKey: '',
  appSecret: '',
  signSecret: '',
  robotCode: '',
  webhookUrl: '',
  enabled: 0,
  welcomeText: ''
})
const dingTalkKbIds = ref<number[]>([])
const dingTalkLoading = ref(false)
const savingDingTalk = ref(false)
const testContent = ref('')
const testResult = ref('')
const testingDingTalk = ref(false)
const testElapsed = ref(0)

async function loadDingTalk() {
  dingTalkLoading.value = true
  try {
    const data = await csAdminApi.getDingTalk()
    Object.assign(dingTalk, data)
    dingTalkKbIds.value = parseJsonArray<number>(data.kbIds)
  } catch (e: any) {
    ElMessage.error('加载钉钉配置失败：' + (e?.message || ''))
  } finally {
    dingTalkLoading.value = false
  }
}

async function saveDingTalk() {
  savingDingTalk.value = true
  try {
    const payload: CsDingTalkChannel = {
      name: dingTalk.value.name,
      appKey: dingTalk.value.appKey,
      appSecret: dingTalk.value.appSecret,
      signSecret: dingTalk.value.signSecret,
      robotCode: dingTalk.value.robotCode,
      webhookUrl: dingTalk.value.webhookUrl,
      enabled: dingTalk.value.enabled,
      kbIds: JSON.stringify(dingTalkKbIds.value),
      welcomeText: dingTalk.value.welcomeText
    }
    const saved = await csAdminApi.saveDingTalk(payload)
    Object.assign(dingTalk, saved)
    dingTalkKbIds.value = parseJsonArray<number>(saved.kbIds)
    ElMessage.success('钉钉渠道配置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally {
    savingDingTalk.value = false
  }
}

async function testDingTalk() {
  if (!testContent.value.trim()) return
  testingDingTalk.value = true
  testResult.value = ''
  testElapsed.value = 0
  const start = Date.now()
  try {
    const answer = await csAdminApi.testDingTalk(testContent.value.trim())
    testResult.value = answer || '（模型没有返回内容）'
    testElapsed.value = Date.now() - start
  } catch (e: any) {
    ElMessage.error('测试失败：' + (e?.message || ''))
  } finally {
    testingDingTalk.value = false
  }
}

// ─────────────────────────────────────────────
// 初始化
// ─────────────────────────────────────────────
async function loadKnowledgeBases() {
  try {
    const result: any = await knowledgeApi.list({ current: 1, size: 200, status: 'ready' })
    const records: any[] = result?.records || result?.data?.records || []
    knowledgeBases.value = records.map((kb: any) => ({ id: kb.id, name: kb.name }))
  } catch {
    // 知识库列表失败不阻塞配置
  }
}

async function loadOverview() {
  try {
    overview.value = await csAdminApi.overview()
  } catch {
    // ignore
  }
}

onMounted(async () => {
  await Promise.all([loadConfig(), loadSsoApps(), loadDingTalk(), loadKnowledgeBases(), loadOverview()])
})
</script>

<style scoped>
.cs-settings-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 30px 44px;
  background: var(--bg-page);
}

.page-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  gap: 22px; flex-wrap: wrap; margin-bottom: 16px;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 7px; }
.page-title { font-family: 'Manrope', 'Noto Sans SC', sans-serif; font-size: 23px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #e0f2fe, #bae6fd); color: #0369a1;
}
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 780px; line-height: 1.65; }
.header-stats { display: flex; gap: 18px; background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px; padding: 10px 18px; }
.head-stat { display: flex; flex-direction: column; align-items: center; gap: 1px; }
.head-stat strong { font-family: 'Manrope', 'Noto Sans SC', sans-serif; font-size: 19px; color: var(--ink-1); }
.head-stat span { font-size: 10.5px; color: var(--ink-4); }

.cs-tabs :deep(.el-tabs__nav-wrap::after) { background: var(--line); }
.cs-tabs :deep(.el-tabs__item) { font-size: 13.5px; font-weight: 600; }

.panel {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 20px 20px;
  box-shadow: var(--shadow-card);
}
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.panel-head h3 { font-size: 15px; font-weight: 700; color: var(--ink-1); }
.panel-sub { font-size: 12px; color: var(--ink-3); line-height: 1.6; }
.config-form :deep(.el-form-item) { margin-bottom: 14px; }
.form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.color-row { display: flex; align-items: center; gap: 10px; }
.color-row :deep(.el-input) { flex: 1; }

.sub-section-title { font-size: 12px; font-weight: 700; color: var(--ink-3); letter-spacing: 0.05em; margin: 8px 0 2px; padding-top: 10px; border-top: 1px solid var(--line-soft); }
.switch-grid { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; }
.switch-grid.no-border { border-top: none; padding-top: 0; }
.sso-mode-item { margin-top: 10px; }
.switch-item { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 8px 2px; }
.switch-label { font-size: 13px; font-weight: 600; color: var(--ink-1); }
.switch-hint { font-size: 11.5px; color: var(--ink-4); margin-top: 1px; }

.page-config-grid { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 18px; align-items: start; }
.preview-wrap { position: sticky; top: 0; }
.preview-label { font-size: 11.5px; font-weight: 700; color: var(--ink-4); letter-spacing: 0.06em; text-transform: uppercase; margin-bottom: 8px; }
.phone {
  width: 100%; max-width: 320px; margin: 0 auto;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 26px;
  box-shadow: var(--shadow-lg);
  padding: 12px;
  --phone-theme: #3D5AFE;
}
.phone-notch { width: 74px; height: 5px; border-radius: 999px; background: var(--line-strong); margin: 2px auto 10px; }
.phone-head { display: flex; align-items: center; gap: 9px; padding: 4px 6px 10px; border-bottom: 1px solid var(--line-soft); }
.phone-logo {
  width: 34px; height: 34px; border-radius: 10px; flex-shrink: 0;
  background: var(--phone-theme); color: #fff;
  display: flex; align-items: center; justify-content: center; font-weight: 800; overflow: hidden;
}
.phone-logo img { width: 100%; height: 100%; object-fit: cover; }
.phone-titles { min-width: 0; }
.phone-title { font-size: 13px; font-weight: 700; color: var(--ink-1); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.phone-sub { font-size: 10px; color: var(--ink-4); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.phone-body { padding: 14px 6px 8px; display: flex; flex-direction: column; gap: 9px; min-height: 190px; }
.phone-bubble {
  font-size: 11.5px; line-height: 1.65; color: var(--ink-2);
  background: var(--bg-subtle); border-radius: 10px; border-top-left-radius: 3px;
  padding: 9px 11px;
  white-space: pre-wrap;
}
.phone-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.phone-chip {
  font-size: 10px; color: var(--phone-theme);
  background: color-mix(in srgb, var(--phone-theme) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--phone-theme) 26%, transparent);
  border-radius: 999px; padding: 3px 8px;
}
.phone-hours { font-size: 10px; color: var(--ink-4); }
.phone-hours.offline { color: var(--warning-ink); }
.phone-input {
  margin: 4px 6px 8px; border: 1px solid var(--line); border-radius: 10px;
  padding: 8px 10px; font-size: 10.5px; color: var(--ink-4);
}
.phone-brand { text-align: center; font-size: 9.5px; color: var(--ink-5); padding-bottom: 6px; }

.mono { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.text-ok { color: var(--success-ink); font-weight: 600; }
.text-muted { color: var(--ink-4); }
.sign-tip { margin-top: 16px; border-top: 1px solid var(--line-soft); padding-top: 12px; }
.sign-tip h4 { font-size: 12.5px; font-weight: 700; color: var(--ink-2); margin-bottom: 5px; }
.sign-tip p { font-size: 12px; color: var(--ink-3); line-height: 1.75; }
.sign-tip code { font-family: 'JetBrains Mono', monospace; font-size: 11px; background: var(--bg-subtle); padding: 1px 5px; border-radius: 4px; overflow-wrap: anywhere; }

.dingtalk-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; align-items: start; }
.test-input { margin-bottom: 10px; }
.test-actions { display: flex; align-items: center; gap: 12px; }
.test-elapsed { font-size: 11.5px; color: var(--ink-4); }
.test-result { margin-top: 14px; border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.test-result-label { font-size: 11px; font-weight: 700; color: var(--ink-3); background: var(--bg-subtle); padding: 6px 12px; }
.test-result-body { padding: 12px; font-size: 13px; line-height: 1.75; color: var(--ink-1); white-space: pre-wrap; word-break: break-word; }
.test-empty { margin-top: 14px; padding: 34px 0; text-align: center; color: var(--ink-4); font-size: 12.5px; display: flex; flex-direction: column; align-items: center; gap: 8px; }

.secret-body { display: flex; flex-direction: column; gap: 12px; }
.secret-warn { font-size: 12.5px; color: var(--warning-ink); background: var(--warning-soft); border-radius: 8px; padding: 10px 12px; line-height: 1.6; }
.secret-row { display: flex; align-items: center; gap: 10px; }
.secret-label { width: 82px; font-size: 12px; color: var(--ink-3); flex-shrink: 0; }
.secret-value {
  flex: 1; min-width: 0;
  font-family: 'JetBrains Mono', monospace; font-size: 12px;
  background: var(--bg-subtle); border: 1px solid var(--line); border-radius: 8px;
  padding: 7px 10px; overflow-wrap: anywhere;
}

@media (max-width: 1100px) {
  .page-config-grid,
  .dingtalk-grid { grid-template-columns: minmax(0, 1fr); }
  .preview-wrap { position: static; }
}
@media (max-width: 720px) {
  .cs-settings-page { padding: 18px 16px 34px; }
  .form-grid-2 { grid-template-columns: 1fr; }
}
</style>
