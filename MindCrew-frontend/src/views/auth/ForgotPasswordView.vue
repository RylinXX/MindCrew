<template>
  <div class="forgot-page">
    <div class="bg-grid"></div>
    <div class="bg-glow glow-1"></div>

    <div class="forgot-card">
      <div class="card-header">
        <div class="logo">
          <div class="logo-icon">
            <MindCrewIcon :size="24" />
          </div>
          <span class="logo-text">MindCrew</span>
        </div>
        <h2>找回密码</h2>
        <p>通过手机验证码重置您的账号密码</p>
      </div>

      <!-- 步骤条 -->
      <el-steps :active="step" align-center class="steps" finish-status="success">
        <el-step title="验证手机号" />
        <el-step title="重置密码" />
        <el-step title="完成" />
      </el-steps>

      <!-- Step 0: 发送验证码 -->
      <el-form
        v-if="step === 0"
        ref="step1Ref"
        :model="form"
        :rules="step1Rules"
        size="large"
        label-position="top"
      >
        <el-form-item label="手机号" prop="phone">
          <el-input
            v-model="form.phone"
            placeholder="请输入注册时绑定的手机号"
            :prefix-icon="Phone"
            clearable
            maxlength="11"
          />
        </el-form-item>
        <el-form-item label="验证码" prop="code">
          <div style="display:flex;gap:10px">
            <el-input v-model="form.code" placeholder="6位验证码" clearable style="flex:1" />
            <el-button
              type="primary"
              :disabled="!!countdown || !form.phone"
              :loading="sendingCode"
              @click="sendCode"
              style="width:120px;flex-shrink:0"
            >
              {{ countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-button type="primary" class="submit-btn" :loading="loading" @click="verifyCode" block>
          下一步
        </el-button>
      </el-form>

      <!-- Step 1: 设置新密码 -->
      <el-form
        v-else-if="step === 1"
        ref="step2Ref"
        :model="form"
        :rules="step2Rules"
        size="large"
        label-position="top"
      >
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            placeholder="请输入新密码（至少6位）"
            :prefix-icon="Lock"
            show-password
            clearable
          />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            :prefix-icon="Lock"
            show-password
            clearable
          />
        </el-form-item>
        <el-button type="primary" class="submit-btn" :loading="loading" @click="resetPassword" block>
          重置密码
        </el-button>
      </el-form>

      <!-- Step 2: 完成 -->
      <div v-else class="success-screen">
        <el-icon size="56" color="#34d399"><CircleCheck /></el-icon>
        <h3>密码重置成功！</h3>
        <p>您的密码已成功重置，请使用新密码登录。</p>
        <el-button type="primary" class="submit-btn" @click="router.push('/login')">
          立即登录
        </el-button>
      </div>

      <div class="back-link">
        <router-link to="/login">
          <el-icon size="13"><ArrowLeft /></el-icon> 返回登录
        </router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Phone, Lock, CircleCheck, ArrowLeft } from '@element-plus/icons-vue'
import { userApi } from '@/api/user'
import MindCrewIcon from '@/components/MindCrewIcon.vue'

const router = useRouter()
const step = ref(0)
const loading = ref(false)
const sendingCode = ref(false)
const countdown = ref(0)

const step1Ref = ref<FormInstance>()
const step2Ref = ref<FormInstance>()

const form = reactive({ phone: '', code: '', newPassword: '', confirmPassword: '' })

const step1Rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { len: 6, message: '验证码为6位', trigger: 'blur' },
  ],
}
const step2Rules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '至少6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_: any, v: string, cb: Function) =>
        v !== form.newPassword ? cb(new Error('两次密码不一致')) : cb(),
      trigger: 'blur',
    },
  ],
}

const sendCode = async () => {
  if (!form.phone || !/^1[3-9]\d{9}$/.test(form.phone)) {
    ElMessage.warning('请先输入正确的手机号')
    return
  }
  sendingCode.value = true
  try {
    await userApi.sendResetCode(form.phone)
    ElMessage.success('验证码已发送')
    countdown.value = 60
    const timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch { /* handled */ }
  finally { sendingCode.value = false }
}

const verifyCode = async () => {
  const valid = await step1Ref.value?.validate().catch(() => false)
  if (!valid) return
  step.value = 1
}

const resetPassword = async () => {
  const valid = await step2Ref.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userApi.resetPassword({ phone: form.phone, code: form.code, newPassword: form.newPassword })
    step.value = 2
  } catch { /* handled */ }
  finally { loading.value = false }
}
</script>

<style scoped>
.forgot-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #0a0e16;
  position: relative;
  overflow: hidden;
}
.bg-grid {
  position: absolute; inset: 0;
  background-image: linear-gradient(rgba(56,189,248,0.04) 1px, transparent 1px), linear-gradient(90deg, rgba(56,189,248,0.04) 1px, transparent 1px);
  background-size: 44px 44px;
  pointer-events: none;
}
.bg-glow { position: absolute; border-radius: 50%; pointer-events: none; filter: blur(80px); }
.glow-1 { width: 400px; height: 400px; top: -100px; left: -100px; background: rgba(37,99,235,0.1); }

.forgot-card {
  width: 420px;
  max-width: calc(100vw - 40px);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: 18px;
  padding: 36px;
  position: relative;
  z-index: 1;
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
}

.card-header { margin-bottom: 28px; }
.logo { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; }
.logo-icon {
  width: 40px; height: 40px;
  background: linear-gradient(135deg, #1e3a5f, #0e2640);
  border: 1px solid rgba(56,189,248,0.25);
  border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
}
.logo-text { font-size: 18px; font-weight: 700; color: #e2e8f0; }
.card-header h2 { font-size: 22px; font-weight: 700; color: #e2e8f0; margin-bottom: 6px; }
.card-header p  { font-size: 13px; color: #64748b; }

.steps { margin-bottom: 28px; }
:deep(.el-step__title) { font-size: 12px !important; }
:deep(.el-step__head.is-finish .el-step__icon) { border-color: #34d399 !important; }
:deep(.el-step__head.is-process .el-step__icon) { border-color: #38bdf8 !important; }

.submit-btn { width: 100% !important; height: 44px !important; font-size: 15px !important; font-weight: 600 !important; margin-top: 6px; }

.success-screen {
  display: flex; flex-direction: column; align-items: center; gap: 14px;
  padding: 24px 0;
}
.success-screen h3 { font-size: 20px; font-weight: 700; color: #e2e8f0; }
.success-screen p  { font-size: 14px; color: #64748b; text-align: center; }

.back-link { margin-top: 20px; text-align: center; }
.back-link a { font-size: 13px; color: #64748b; text-decoration: none; display: inline-flex; align-items: center; gap: 4px; }
.back-link a:hover { color: var(--primary); }

:deep(.el-input__wrapper) {
  background: var(--bg-elevated) !important;
  box-shadow: 0 0 0 1px var(--border) !important;
}
:deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px var(--primary) !important; }
:deep(.el-input__inner) { color: #e2e8f0 !important; }
:deep(.el-form-item__label) { color: #94a3b8 !important; }
</style>
