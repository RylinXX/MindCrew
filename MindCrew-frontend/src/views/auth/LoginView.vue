<template>
  <div class="login-page">
    <!-- 背景流光 -->
    <svg class="bg-streaks" viewBox="0 0 1600 900" preserveAspectRatio="none" aria-hidden="true">
      <defs>
        <linearGradient id="streak" x1="0" x2="1" y1="0" y2="0">
          <stop offset="0%"   stop-color="#3D5AFE" stop-opacity="0" />
          <stop offset="50%"  stop-color="#A78BFA" stop-opacity="0.18" />
          <stop offset="100%" stop-color="#3D5AFE" stop-opacity="0" />
        </linearGradient>
      </defs>
      <path d="M-100,520 C 400,420 1000,640 1700,440" stroke="url(#streak)" stroke-width="2" fill="none" />
      <path d="M-100,640 C 500,560 1100,720 1700,560" stroke="url(#streak)" stroke-width="1.5" fill="none" opacity="0.7" />
      <path d="M-100,760 C 400,680 1100,820 1700,700" stroke="url(#streak)" stroke-width="1"   fill="none" opacity="0.5" />
      <path d="M-100,260 C 600,160 1000,340 1700,200" stroke="url(#streak)" stroke-width="1.5" fill="none" opacity="0.5" />
    </svg>

    <!-- 主卡片 -->
    <div class="login-shell">
      <!-- 左侧暗色品牌 -->
      <section class="brand-pane">
        <header class="brand-row">
          <div class="brand-mark">
            <span class="mark-letter">C</span>
            <span class="mark-spark"></span>
          </div>
          <span class="brand-name">MindCrew</span>
        </header>

        <h1 class="hero">
          文档智能问答
          <span class="hero-accent">知识随取随用</span>
        </h1>

        <p class="hero-desc">
          基于 RAG 架构，融合向量检索与大语言模型，<br />
          让您的文档库变成有问必答的智能知识助手。
        </p>

        <div class="feat-grid">
          <div v-for="f in features" :key="f.text" class="feat-card">
            <span class="feat-ic" :style="{ background: f.tone }">
              <el-icon :size="14" :color="f.fg"><component :is="f.icon" /></el-icon>
            </span>
            <span class="feat-text">{{ f.text }}</span>
          </div>
        </div>

        <div class="pill-row">
          <span class="pill"><el-icon :size="11"><Search /></el-icon> 语义检索</span>
          <span class="pill"><el-icon :size="11"><Lightning /></el-icon> 流式输出</span>
          <span class="pill"><el-icon :size="11"><Link /></el-icon> 来源引用</span>
        </div>

        <!-- CSS 立体文档插画 -->
        <figure class="illus" aria-hidden="true">
          <div class="illus-glow"></div>
          <div class="illus-pedestal"></div>
          <div class="illus-card">
            <span class="card-line w1"></span>
            <span class="card-line w2"></span>
            <span class="card-line w3"></span>
          </div>
          <div class="illus-lens">
            <span class="lens-ring"></span>
            <span class="lens-handle"></span>
          </div>
          <span class="illus-spark s1"></span>
          <span class="illus-spark s2"></span>
          <span class="illus-spark s3"></span>
        </figure>
      </section>

      <!-- 右侧表单 -->
      <section class="form-pane">
        <nav class="tabs">
          <button
            class="tab"
            :class="{ active: activeTab === 'login' }"
            @click="activeTab = 'login'"
          >登录</button>
          <button
            class="tab"
            :class="{ active: activeTab === 'register' }"
            @click="activeTab = 'register'"
          >注册</button>
          <span class="tab-bar" :class="activeTab === 'register' ? 'right' : 'left'"></span>
        </nav>

        <transition name="form-fade" mode="out-in">
          <div v-if="activeTab === 'login'" key="login" class="form-block">
            <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" size="large">
              <el-form-item prop="username">
                <el-input v-model="loginForm.username" placeholder="用户名" :prefix-icon="User" clearable />
              </el-form-item>
              <el-form-item prop="password">
                <el-input
                  v-model="loginForm.password"
                  type="password"
                  placeholder="密码"
                  :prefix-icon="Lock"
                  show-password
                  clearable
                  @keyup.enter="handleLogin"
                />
              </el-form-item>
              <div class="row-between">
                <el-checkbox v-model="rememberMe"><span class="cb-label">记住我</span></el-checkbox>
                <router-link to="/forgot-password" class="link">忘记密码？</router-link>
              </div>
              <button class="cta" :class="{ loading }" :disabled="loading" @click="handleLogin">
                <span>{{ loading ? '登录中…' : '立即登录' }}</span>
                <el-icon v-if="!loading" :size="16"><Right /></el-icon>
              </button>
            </el-form>
          </div>

          <div v-else key="register" class="form-block">
            <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" size="large">
              <el-form-item prop="username">
                <el-input v-model="registerForm.username" placeholder="用户名（4-20位字符）" :prefix-icon="User" clearable />
              </el-form-item>
              <el-form-item prop="nickname">
                <el-input v-model="registerForm.nickname" placeholder="昵称（选填）" :prefix-icon="Avatar" clearable />
              </el-form-item>
              <el-form-item prop="password">
                <el-input
                  v-model="registerForm.password"
                  type="password"
                  placeholder="密码（6位以上）"
                  :prefix-icon="Lock"
                  show-password
                  clearable
                />
              </el-form-item>
              <el-form-item prop="confirmPassword">
                <el-input
                  v-model="registerForm.confirmPassword"
                  type="password"
                  placeholder="确认密码"
                  :prefix-icon="Lock"
                  show-password
                  clearable
                />
              </el-form-item>

              <!-- 任务 7 · 部门 + 职位（可选） -->
              <el-form-item prop="departmentId">
                <el-select
                  v-model="registerForm.departmentId"
                  placeholder="部门（选填）"
                  clearable
                  size="large"
                  style="width:100%"
                  @change="onRegDeptChange"
                >
                  <template #prefix><el-icon :size="14"><OfficeBuilding /></el-icon></template>
                  <el-option v-for="d in regDepts" :key="d.id" :label="d.name" :value="d.id" />
                </el-select>
              </el-form-item>
              <el-form-item prop="positionId">
                <el-select
                  v-model="registerForm.positionId"
                  placeholder="职位（选填 · 不选只能看公开知识库）"
                  clearable
                  filterable
                  size="large"
                  style="width:100%"
                  @keyup.enter="handleRegister"
                >
                  <template #prefix><el-icon :size="14"><UserFilled /></el-icon></template>
                  <el-option
                    v-for="p in filteredRegPositions"
                    :key="p.id"
                    :label="`${p.name} (${p.code})`"
                    :value="p.id"
                  />
                </el-select>
              </el-form-item>

              <button class="cta" :class="{ loading }" :disabled="loading" @click="handleRegister">
                <span>{{ loading ? '注册中…' : '创建账号' }}</span>
                <el-icon v-if="!loading" :size="16"><Right /></el-icon>
              </button>
            </el-form>
          </div>
        </transition>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock, Avatar, Search, Lightning, Link, Right, OfficeBuilding, UserFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { userApi } from '@/api/user'
import { departmentApi, positionApi, type Department, type Position } from '@/api/orgAcl'

const router = useRouter()
const route  = useRoute()
const userStore = useUserStore()

const activeTab = ref<'login' | 'register'>('login')
const loading   = ref(false)
const rememberMe = ref(false)

const loginFormRef    = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive<{
  username: string; nickname: string; password: string; confirmPassword: string
  departmentId: number | null; positionId: number | null
}>({
  username: '', nickname: '', password: '', confirmPassword: '',
  departmentId: null, positionId: null,
})

// 任务 7 · 部门 / 职位 字典（注册下拉用）
const regDepts = ref<Department[]>([])
const regPositions = ref<Position[]>([])
const filteredRegPositions = computed(() =>
  registerForm.departmentId
    ? regPositions.value.filter(p => p.departmentId === registerForm.departmentId || p.departmentId == null)
    : regPositions.value
)
const onRegDeptChange = () => {
  if (registerForm.positionId) {
    const p = regPositions.value.find(x => x.id === registerForm.positionId)
    if (p && p.departmentId && p.departmentId !== registerForm.departmentId) {
      registerForm.positionId = null
    }
  }
}
onMounted(async () => {
  try {
    const [dRes, pRes]: any = await Promise.all([departmentApi.list(), positionApi.list()])
    regDepts.value = dRes?.data ?? dRes ?? []
    regPositions.value = pRes?.data ?? pRes ?? []
  } catch { /* 字典加载失败不阻塞注册 */ }
})

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码',   trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 20, message: '4-20位字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '至少6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule: any, value: string, callback: Function) => {
        value !== registerForm.password ? callback(new Error('两次密码不一致')) : callback()
      },
      trigger: 'blur',
    },
  ],
}

const features = [
  { icon: 'Search',     fg: '#60A5FA', tone: 'rgba(96, 165, 250, 0.14)', text: '多路召回检索' },
  { icon: 'Star',       fg: '#FBBF24', tone: 'rgba(251, 191, 36, 0.14)', text: 'Cross-Encoder 重排' },
  { icon: 'Connection', fg: '#34D399', tone: 'rgba(52, 211, 153, 0.14)', text: 'MCP 工具集成' },
  { icon: 'Document',   fg: '#A78BFA', tone: 'rgba(167, 139, 250, 0.14)', text: '来源可溯引用' },
]

const handleLogin = async () => {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login({ username: loginForm.username, password: loginForm.password })
    ElMessage.success('登录成功，欢迎使用 MindCrew！')
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch { /* handled in request.ts */ }
  finally { loading.value = false }
}

const handleRegister = async () => {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userApi.register({
      username: registerForm.username,
      password: registerForm.password,
      nickname: registerForm.nickname || undefined,
      departmentId: registerForm.departmentId ?? undefined,
      positionId: registerForm.positionId ?? undefined,
    })
    ElMessage.success('注册成功，请登录')
    loginForm.username = registerForm.username
    loginForm.password = registerForm.password
    activeTab.value = 'login'
  } catch { /* handled */ }
  finally { loading.value = false }
}
</script>

<style scoped>
/* ─────────────────────────────────────────────
   Page & background
   ───────────────────────────────────────────── */
.login-page {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1200px 700px at 12% 30%, rgba(167, 139, 250, 0.12), transparent 60%),
    radial-gradient(1200px 700px at 92% 78%, rgba(124, 140, 255, 0.10), transparent 60%),
    linear-gradient(180deg, #ECEEF7 0%, #F2F3FA 60%, #F0EEF8 100%);
  overflow: hidden;
}
.bg-streaks {
  position: absolute;
  inset: -10%;
  width: 120%;
  height: 120%;
  pointer-events: none;
  z-index: 0;
}

/* ─────────────────────────────────────────────
   Outer card
   ───────────────────────────────────────────── */
.login-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  width: min(1100px, calc(100vw - 64px));
  height: min(720px, calc(100vh - 64px));
  background: #FFFFFF;
  border-radius: 22px;
  overflow: hidden;
  box-shadow:
    0 40px 90px rgba(15, 23, 42, 0.16),
    0 12px 32px rgba(15, 23, 42, 0.08),
    0 0 0 1px rgba(255, 255, 255, 0.6) inset;
}

/* ─────────────────────────────────────────────
   Left dark brand pane
   ───────────────────────────────────────────── */
.brand-pane {
  position: relative;
  padding: 38px 40px 0;
  background:
    radial-gradient(600px 400px at -10% -10%, rgba(124, 140, 255, 0.18), transparent 60%),
    radial-gradient(500px 400px at 110% 110%, rgba(56, 189, 248, 0.10), transparent 60%),
    linear-gradient(155deg, #0F1B33 0%, #0A1226 55%, #060A18 100%);
  color: #E6EAF6;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* very faint grid */
.brand-pane::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,0.025) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse at 30% 20%, black, transparent 70%);
  pointer-events: none;
}

.brand-row { display: flex; align-items: center; gap: 12px; }
.brand-mark {
  position: relative;
  width: 38px; height: 38px;
  border-radius: 11px;
  background: linear-gradient(135deg, #6E5AE6 0%, #4F47C4 55%, #2E1B9A 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow:
    0 10px 26px rgba(110, 90, 230, 0.45),
    inset 0 1px 0 rgba(255,255,255,0.25);
}
.mark-letter {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-weight: 800;
  font-size: 19px;
  color: #fff;
  letter-spacing: -0.02em;
  line-height: 1;
}
.mark-spark {
  position: absolute;
  top: 6px; right: 6px;
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #C7C0FF;
  box-shadow: 0 0 8px #A78BFA, 0 0 14px #A78BFA;
}
.brand-name {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 18px;
  font-weight: 800;
  color: #F5F7FF;
  letter-spacing: -0.02em;
}

.hero {
  margin-top: 36px;
  font-family: 'Manrope', 'Noto Sans SC', 'PingFang SC', sans-serif;
  font-size: 30px;
  font-weight: 700;
  color: #F4F6FF;
  line-height: 1.32;
  letter-spacing: -0.02em;
}
.hero-accent {
  display: block;
  background: linear-gradient(95deg, #8E7BFF 0%, #5C7BFF 50%, #4E6BFF 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}

.hero-desc {
  margin-top: 18px;
  font-size: 13.5px;
  line-height: 1.75;
  color: #94A0BD;
  letter-spacing: 0.01em;
}

.feat-grid {
  margin-top: 26px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.feat-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 11px;
  font-size: 13px;
  color: #D2D8EC;
  backdrop-filter: blur(6px);
}
.feat-ic {
  width: 26px; height: 26px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.feat-text { font-weight: 500; }

.pill-row {
  margin-top: 14px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.06);
  font-size: 12px;
  color: #A6AEC8;
}
.pill :deep(.el-icon) { color: #8E7BFF; }

/* ─────────────────────────────────────────────
   CSS 立体文档插画
   ───────────────────────────────────────────── */
.illus {
  position: relative;
  margin: auto auto -10px;
  width: 240px;
  height: 200px;
  flex-shrink: 0;
  align-self: center;
}
.illus-glow {
  position: absolute;
  left: 50%;
  bottom: 18px;
  width: 200px;
  height: 60px;
  transform: translateX(-50%);
  background: radial-gradient(ellipse at center, rgba(124, 140, 255, 0.55) 0%, rgba(124, 140, 255, 0) 70%);
  filter: blur(14px);
}
.illus-pedestal {
  position: absolute;
  left: 50%;
  bottom: 20px;
  width: 160px;
  height: 28px;
  transform: translateX(-50%) perspective(420px) rotateX(58deg);
  background:
    linear-gradient(180deg, #5870FF, #3447D4);
  border-radius: 12px;
  box-shadow:
    0 14px 28px rgba(52, 71, 212, 0.55),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}
.illus-pedestal::before {
  content: '';
  position: absolute;
  inset: 4px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(255,255,255,0.18), rgba(255,255,255,0));
}
.illus-card {
  position: absolute;
  left: 50%;
  bottom: 48px;
  width: 132px;
  height: 96px;
  transform: translateX(-50%) rotate(-4deg);
  background: linear-gradient(150deg, #F0F3FF 0%, #C8D0FF 100%);
  border-radius: 10px;
  box-shadow:
    0 18px 30px rgba(15, 23, 60, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.8);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.card-line {
  display: block;
  height: 6px;
  border-radius: 3px;
  background: linear-gradient(90deg, #6E83FF, rgba(110, 131, 255, 0.2));
}
.card-line.w1 { width: 60%; }
.card-line.w2 { width: 80%; }
.card-line.w3 { width: 45%; }

.illus-lens {
  position: absolute;
  right: 32px;
  bottom: 56px;
  width: 38px;
  height: 38px;
  filter: drop-shadow(0 6px 14px rgba(110, 131, 255, 0.5));
}
.lens-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 30%, rgba(255,255,255,0.85), rgba(180, 200, 255, 0.55));
  border: 2px solid rgba(110, 131, 255, 0.9);
  box-shadow: 0 0 18px rgba(124, 140, 255, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.7);
}
.lens-handle {
  position: absolute;
  right: -6px;
  bottom: -4px;
  width: 14px;
  height: 4px;
  background: linear-gradient(90deg, #4F62E6, #7184FF);
  border-radius: 3px;
  transform: rotate(45deg);
}

.illus-spark {
  position: absolute;
  border-radius: 50%;
  background: #C8D0FF;
  box-shadow: 0 0 8px #A78BFA;
}
.illus-spark.s1 { width: 4px; height: 4px; top: 16px;  left: 40px;  opacity: 0.7; }
.illus-spark.s2 { width: 3px; height: 3px; top: 32px;  right: 28px; opacity: 0.8; }
.illus-spark.s3 { width: 5px; height: 5px; bottom: 4px; left: 22px;  opacity: 0.6; }

/* ─────────────────────────────────────────────
   Right form pane
   ───────────────────────────────────────────── */
.form-pane {
  padding: 56px 56px 40px;
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
}

.tabs {
  position: relative;
  display: flex;
  justify-content: center;
  gap: 64px;             /* center-to-center distance becomes 32 + 64 + 32 = ~96px */
  margin-bottom: 36px;
}
.tab {
  background: none;
  border: none;
  padding: 8px 4px;
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 18px;
  font-weight: 600;
  color: var(--ink-3);
  cursor: pointer;
  letter-spacing: 0.02em;
  transition: color 200ms var(--ease);
}
.tab.active { color: var(--ink-1); }
.tab-bar {
  position: absolute;
  bottom: -2px;
  left: 50%;
  width: 40px;
  margin-left: -20px;    /* center on container center */
  height: 3px;
  background: linear-gradient(90deg, var(--brand-hover), var(--brand));
  border-radius: 2px;
  transition: transform 220ms var(--ease);
}
.tab-bar.left  { transform: translateX(-48px); }   /* offset to tab1 center */
.tab-bar.right { transform: translateX(48px);  }   /* offset to tab2 center */

.form-block { display: flex; flex-direction: column; }

.row-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 4px 0 22px;
}
.cb-label { color: var(--ink-2); font-size: 13px; }
.link {
  color: var(--brand);
  font-size: 13px;
  font-weight: 500;
  transition: opacity 200ms var(--ease);
}
.link:hover { opacity: 0.78; }

/* Big CTA — gradient with arrow */
.cta {
  width: 100%;
  height: 52px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 0 22px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(180deg, #6075FF 0%, #3D5AFE 55%, #3247D6 100%);
  color: #fff;
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.02em;
  cursor: pointer;
  box-shadow:
    0 14px 30px rgba(61, 90, 254, 0.36),
    0 4px 10px rgba(61, 90, 254, 0.20),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
  transition: filter 200ms var(--ease), box-shadow 200ms var(--ease);
}
.cta:hover:not(:disabled) {
  filter: brightness(1.05);
  box-shadow:
    0 18px 36px rgba(61, 90, 254, 0.42),
    0 6px 12px rgba(61, 90, 254, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.28);
}
.cta:disabled { cursor: not-allowed; opacity: 0.75; }
.cta :deep(.el-icon) { margin-right: -4px; }

/* ── Element Plus form inputs — flat, neutral ── */
.form-block :deep(.el-input__wrapper) {
  background: #FFFFFF !important;
  border-radius: 10px !important;
  box-shadow: 0 0 0 1px var(--line) !important;
  height: 50px;
  padding: 0 16px !important;
}
.form-block :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--line-strong) !important;
}
.form-block :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--brand), 0 0 0 4px var(--brand-glow) !important;
}
.form-block :deep(.el-input__inner) {
  font-size: 14.5px !important;
  color: var(--ink-1) !important;
}
.form-block :deep(.el-input__inner::placeholder) {
  color: var(--ink-4) !important;
}
.form-block :deep(.el-input__prefix-inner .el-icon) {
  color: var(--ink-3) !important;
  font-size: 16px;
}
.form-block :deep(.el-form-item) { margin-bottom: 14px; }

/* ─────────────────────────────────────────────
   Form transitions (no slide → no shake)
   ───────────────────────────────────────────── */
.form-fade-enter-active { transition: opacity 200ms ease; }
.form-fade-leave-active { transition: opacity 120ms ease; }
.form-fade-enter-from   { opacity: 0; }
.form-fade-leave-to     { opacity: 0; }

/* ─────────────────────────────────────────────
   Responsive
   ───────────────────────────────────────────── */
@media (max-width: 880px) {
  .login-shell {
    grid-template-columns: 1fr;
    height: auto;
    max-height: calc(100vh - 32px);
    width: calc(100vw - 32px);
    overflow-y: auto;
  }
  .brand-pane { padding: 32px 32px; }
  .illus { display: none; }
  .form-pane { padding: 32px; }
}
</style>
