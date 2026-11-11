<template>
  <div class="register-page">
    <div class="bg-decoration">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
    </div>

    <div class="register-card">
      <div class="card-header">
        <div class="logo">
          <div class="logo-icon">
            <MindCrewIcon :size="24" />
          </div>
          <span class="logo-text">MindCrew</span>
        </div>
        <h2>创建账号</h2>
        <p>加入 MindCrew，构建您的专属智能知识库</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        label-position="top"
      >
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="3-20个字符" :prefix-icon="User" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="form.nickname" placeholder="显示名称" :prefix-icon="UserFilled" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="可选，用于找回密码" :prefix-icon="Phone" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="6-20个字符"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再次输入密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <!-- 任务 7 · 部门 + 职位（可选） -->
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="部门" prop="departmentId">
              <el-select v-model="form.departmentId" placeholder="可选" clearable style="width:100%" @change="onDeptChange">
                <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="职位" prop="positionId">
              <el-select v-model="form.positionId" placeholder="可选" clearable filterable style="width:100%">
                <el-option v-for="p in filteredPositions" :key="p.id"
                           :label="`${p.name} (${p.code})`" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <div class="org-tip">
          <el-icon size="11"><InfoFilled /></el-icon>
          可不填 · 注册后管理员会按实际身份分配 · 未分配职位时仅能访问公开知识库
        </div>

        <el-button
          class="register-btn"
          type="primary"
          :loading="loading"
          @click="handleRegister"
        >
          {{ loading ? '注册中...' : '立即注册' }}
        </el-button>
      </el-form>

      <div class="card-footer">
        已有账号？
        <router-link to="/login" class="login-link">立即登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, UserFilled, Phone, Lock, InfoFilled } from '@element-plus/icons-vue'
import { userApi } from '@/api/user'
import { departmentApi, positionApi, type Department, type Position } from '@/api/orgAcl'
import MindCrewIcon from '@/components/MindCrewIcon.vue'

const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive<{
  username: string
  nickname: string
  phone: string
  password: string
  confirmPassword: string
  departmentId: number | null
  positionId: number | null
}>({
  username: '',
  nickname: '',
  phone: '',
  password: '',
  confirmPassword: '',
  departmentId: null,
  positionId: null,
})

// 部门 + 职位字典
const depts = ref<Department[]>([])
const positions = ref<Position[]>([])
const filteredPositions = computed(() =>
  form.departmentId
    ? positions.value.filter(p => p.departmentId === form.departmentId || p.departmentId == null)
    : positions.value
)
const onDeptChange = () => {
  // 切换部门时，如果当前选中职位不属于新部门，清空
  if (form.positionId) {
    const p = positions.value.find(x => x.id === form.positionId)
    if (p && p.departmentId && p.departmentId !== form.departmentId) {
      form.positionId = null
    }
  }
}
onMounted(async () => {
  try {
    const [dRes, pRes]: any = await Promise.all([departmentApi.list(), positionApi.list()])
    depts.value = dRes?.data ?? dRes ?? []
    positions.value = pRes?.data ?? pRes ?? []
  } catch { /* 字典加载失败不阻塞注册 */ }
})

const validateConfirmPwd = (_rule: any, value: string, callback: any) => {
  if (value !== form.password) {
    callback(new Error('两次密码不一致'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度为3-20个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPwd, trigger: 'blur' }
  ],
  phone: [
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

const handleRegister = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userApi.register({
      username: form.username,
      password: form.password,
      phone: form.phone || undefined,
      nickname: form.nickname || undefined,
      departmentId: form.departmentId ?? undefined,
      positionId: form.positionId ?? undefined,
    })
    ElMessage.success('注册成功！请登录')
    router.push('/login')
  } catch {
    // 错误已在 request.ts 中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #0f172a 100%);
  position: relative;
  overflow: hidden;
}

.bg-decoration { position: absolute; inset: 0; pointer-events: none; }
.circle { position: absolute; border-radius: 50%; }
.circle-1 { width: 500px; height: 500px; top: -150px; right: -100px; background: radial-gradient(circle, rgba(37,99,235,0.1), transparent); }
.circle-2 { width: 350px; height: 350px; bottom: -100px; left: -80px; background: radial-gradient(circle, rgba(124,58,237,0.1), transparent); }

.register-card {
  width: 520px;
  max-width: calc(100vw - 48px);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 20px;
  padding: 40px;
  backdrop-filter: blur(20px);
  box-shadow: 0 25px 60px rgba(0, 0, 0, 0.5);
}

.card-header { text-align: center; margin-bottom: 32px; }

.logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.logo-icon {
  width: 44px;
  height: 44px;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.logo-text {
  font-size: 22px;
  font-weight: 800;
  color: #fff;
  letter-spacing: 1px;
}

.card-header h2 {
  font-size: 22px;
  font-weight: 700;
  color: #f1f5f9;
  margin-bottom: 6px;
}

.card-header p { color: #64748b; font-size: 14px; }

:deep(.el-form-item__label) { color: #94a3b8 !important; font-size: 13px; }
:deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06) !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  box-shadow: none !important;
}
:deep(.el-input__inner) { color: #e2e8f0 !important; }

.register-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  background: linear-gradient(135deg, #2563eb, #7c3aed) !important;
  border: none !important;
  border-radius: 10px !important;
  margin-top: 8px;
}

/* 任务 7 · 部门/职位提示 */
.org-tip {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: -6px 0 14px;
  font-size: 11.5px;
  color: #94a3b8;
  line-height: 1.5;
}

.card-footer {
  text-align: center;
  margin-top: 20px;
  color: #64748b;
  font-size: 14px;
}

.login-link {
  color: #60a5fa;
  text-decoration: none;
  font-weight: 500;
  margin-left: 4px;
}
</style>
