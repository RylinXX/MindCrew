<template>
  <div class="profile-page">
    <!-- 面包屑 -->
    <nav class="crumb">
      <span class="crumb-item ghost">个人中心</span>
      <el-icon :size="11" class="crumb-sep"><ArrowRight /></el-icon>
      <span class="crumb-item">账户信息</span>
    </nav>

    <!-- ──────────────────── HERO ──────────────────── -->
    <section class="hero">
      <!-- 装饰球 -->
      <div class="hero-decor">
        <span class="orb orb-1"></span>
        <span class="orb orb-2"></span>
        <span class="orb orb-3"></span>
      </div>

      <div class="hero-grid">
        <div class="hero-avatar-wrap" @click="triggerAvatarUpload">
          <div class="hero-avatar">
            <img v-if="avatarPreview || userInfo?.avatar" :src="avatarPreview || userInfo?.avatar" alt="avatar" />
            <span v-else class="avatar-letter">{{ (userInfo?.nickname || userInfo?.username || '管').charAt(0).toUpperCase() }}</span>
            <div class="avatar-mask" :class="{ uploading: avatarUploading }">
              <el-icon :size="22"><Camera /></el-icon>
            </div>
          </div>
          <input
            ref="avatarInputRef"
            type="file"
            accept="image/jpeg,image/png,image/webp,image/gif"
            hidden
            @change="onAvatarFileChange"
          />
        </div>

        <div class="hero-info">
          <h1 class="hero-name">{{ userInfo?.nickname || userInfo?.username || '管理员' }}</h1>

          <div class="hero-row">
            <span class="hero-handle">@{{ userInfo?.username || 'admin' }}</span>
            <span class="hero-badge" :class="userInfo?.role">
              <el-icon :size="11"><Medal /></el-icon>
              <span>{{ roleLabel }}</span>
            </span>
          </div>

          <p class="hero-desc">{{ heroDesc }}</p>

          <div class="hero-meta">
            <div class="meta-row">
              <span class="meta-ic"><el-icon :size="13"><Calendar /></el-icon></span>
              <span class="meta-k">注册时间</span>
              <span class="meta-v">{{ formatDate(userInfo?.createTime) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-ic"><el-icon :size="13"><Clock /></el-icon></span>
              <span class="meta-k">最近登录</span>
              <span class="meta-v">{{ formatDateTime(userInfo?.lastLogin) }}</span>
            </div>
          </div>
        </div>

        <button class="hero-cta" @click="scrollToForm">
          <el-icon :size="14"><EditPen /></el-icon>
          <span>编辑资料</span>
        </button>
      </div>
    </section>

    <!-- ──────────────────── STATS ──────────────────── -->
    <section class="stats">
      <article v-for="s in stats" :key="s.key" class="stat-card">
        <div class="stat-head">
          <span class="stat-ic" :style="{ background: s.iconBg, color: s.iconColor }">
            <el-icon :size="18"><component :is="s.icon" /></el-icon>
          </span>
          <span class="stat-label">{{ s.label }}</span>
        </div>
        <div class="stat-num">
          <CountUp :end-val="parseFloat(s.value)" :duration="1.4" :options="{ useEasing: true, decimalPlaces: s.key === 'duration' ? 1 : 0 }" />
          <span v-if="s.suffix" class="stat-suffix">{{ s.suffix }}</span>
        </div>
        <div class="stat-foot">
          <span class="stat-trend" :class="{ up: s.trendUp, down: !s.trendUp }">
            <el-icon :size="10"><component :is="s.trendUp ? CaretTop : CaretBottom" /></el-icon>
            {{ s.trend }}
          </span>
          <svg class="spark" viewBox="0 0 80 24" preserveAspectRatio="none" aria-hidden="true">
            <path :d="s.spark" :stroke="s.iconColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
      </article>
    </section>

    <!-- ──────────────────── TABS ──────────────────── -->
    <nav class="tabs" ref="tabNavRef">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :ref="el => setTabRef(tab.key, el)"
        class="tab"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <el-icon :size="14"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </button>
      <span class="tab-bar" :style="indicatorStyle"></span>
    </nav>

    <!-- ──────────────────── CONTENT (基本信息) ──────────────────── -->
    <section v-if="activeTab === 'basic'" class="content">
      <!-- 左：表单 -->
      <div class="card form-card">
        <header class="card-head">
          <div>
            <h2 class="card-title">基本信息</h2>
            <p class="card-sub">维护你的基础账号信息，部分字段会用于 Agent 个性化</p>
          </div>
        </header>

        <form class="profile-form" @submit.prevent="saveBasicInfo">
          <div class="field">
            <div class="field-lbl">
              <span class="field-name">昵称</span>
              <span class="field-hint">在系统中显示的名称</span>
            </div>
            <input v-model="basicForm.nickname" class="field-input" placeholder="你的称呼" />
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">手机号</span>
              <span class="field-hint">用于账号安全和通知</span>
            </div>
            <input v-model="basicForm.phone" class="field-input" placeholder="138****8888" />
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">邮箱</span>
              <span class="field-hint">用于接收重要通知</span>
            </div>
            <input class="field-input disabled" placeholder="admin@docmind.com" disabled />
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">角色</span>
              <span class="field-hint">当前账号角色</span>
            </div>
            <div class="field-static">{{ roleLabel }}</div>
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">所属团队</span>
              <span class="field-hint">所属的团队或组织</span>
            </div>
            <input class="field-input disabled" placeholder="DocMind 团队" disabled />
          </div>

          <div class="form-foot">
            <button type="submit" class="save-btn" :disabled="basicLoading">
              <span v-if="!basicLoading">保存修改</span>
              <span v-else class="loading-wrap">
                <el-icon class="spin"><Loading /></el-icon>保存中…
              </span>
            </button>
          </div>
        </form>
      </div>

      <!-- 右：安全与账户 -->
      <aside class="card side-card">
        <header class="card-head">
          <h2 class="card-title">安全与账户</h2>
        </header>

        <ul class="account-list">
          <li class="acc-row" @click="activeTab = 'security'">
            <span class="acc-ic" style="background: rgba(61,90,254,0.10); color: var(--brand)">
              <el-icon :size="16"><Lock /></el-icon>
            </span>
            <div class="acc-body">
              <div class="acc-title">修改密码</div>
              <div class="acc-sub">定期修改密码，保障账号安全</div>
            </div>
            <el-icon :size="14" class="acc-arrow"><ArrowRight /></el-icon>
          </li>

          <li class="acc-row">
            <span class="acc-ic" style="background: rgba(14,165,233,0.12); color: #0EA5E9">
              <el-icon :size="16"><Monitor /></el-icon>
            </span>
            <div class="acc-body">
              <div class="acc-title">登录设备管理</div>
              <div class="acc-sub">查看和管理登录设备</div>
            </div>
            <el-icon :size="14" class="acc-arrow"><ArrowRight /></el-icon>
          </li>

          <li class="acc-row">
            <span class="acc-ic" style="background: rgba(16,185,129,0.12); color: #10B981">
              <el-icon :size="16"><Aim /></el-icon>
            </span>
            <div class="acc-body">
              <div class="acc-title">双因素认证</div>
              <div class="acc-sub">为账号增加额外安全保护</div>
            </div>
            <span class="acc-flag warn">未开启</span>
            <el-icon :size="14" class="acc-arrow"><ArrowRight /></el-icon>
          </li>

          <li class="acc-row">
            <span class="acc-ic" style="background: rgba(139,92,246,0.12); color: #8B5CF6">
              <el-icon :size="16"><Key /></el-icon>
            </span>
            <div class="acc-body">
              <div class="acc-title">API 密钥管理</div>
              <div class="acc-sub">管理你的 API 访问密钥</div>
            </div>
            <el-icon :size="14" class="acc-arrow"><ArrowRight /></el-icon>
          </li>

          <li class="acc-row danger">
            <span class="acc-ic" style="background: rgba(239,68,68,0.12); color: #EF4444">
              <el-icon :size="16"><WarningFilled /></el-icon>
            </span>
            <div class="acc-body">
              <div class="acc-title">账号注销</div>
              <div class="acc-sub">永久注销账号及所有数据</div>
            </div>
            <el-icon :size="14" class="acc-arrow"><ArrowRight /></el-icon>
          </li>
        </ul>
      </aside>
    </section>

    <!-- ──────────────────── CONTENT (安全/偏好占位) ──────────────────── -->
    <section v-else-if="activeTab === 'security'" class="content single">
      <div class="card placeholder">
        <div class="ph-ic"><el-icon :size="24"><Lock /></el-icon></div>
        <h3>安全设置</h3>
        <p>密码、双因素认证、API 密钥等安全相关配置即将在此页面提供。</p>
      </div>
    </section>

    <section v-else class="content single">
      <div class="card placeholder">
        <div class="ph-ic"><el-icon :size="24"><Setting /></el-icon></div>
        <h3>偏好设置</h3>
        <p>个性化偏好、Agent 行为定制、通知规则等设置即将在此页面提供。</p>
      </div>
    </section>

    <!-- ──────────────────── LOGIN HISTORY ──────────────────── -->
    <section class="login-card card">
      <header class="card-head">
        <h2 class="card-title">最近登录记录</h2>
        <button class="link-btn">
          <el-icon :size="12"><View /></el-icon>查看全部
        </button>
      </header>

      <table class="login-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>设备</th>
            <th>IP 地址</th>
            <th>位置</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(log, i) in loginHistory" :key="i">
            <td class="mono">{{ log.time }}</td>
            <td>
              <span class="dev">
                <span class="dev-ic"><el-icon :size="13"><component :is="log.device.includes('Phone') || log.device.includes('iOS') ? 'Iphone' : 'Monitor'" /></el-icon></span>
                {{ log.device }}
              </span>
            </td>
            <td class="mono">{{ log.ip }}</td>
            <td>{{ log.location }}</td>
            <td>
              <span v-if="log.isCurrent" class="status current">当前登录</span>
              <span v-else class="status normal">正常登录</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch, nextTick, markRaw } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { userApi } from '@/api/user'
import {
  UserFilled, Medal, Clock, Lock, Camera, EditPen, ArrowRight,
  Key, Monitor, Phone, WarningFilled, Loading,
  FolderOpened, MagicStick, Histogram, Timer, Setting,
  Calendar, View, CaretTop, CaretBottom, Aim,
} from '@element-plus/icons-vue'

const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)

const activeTab = ref('basic')
const basicLoading = ref(false)
const avatarUploading = ref(false)
const avatarPreview = ref<string>('')
const avatarInputRef = ref<HTMLInputElement>()

const tabs = [
  { key: 'basic',      label: '基本信息', icon: markRaw(UserFilled) },
  { key: 'security',   label: '安全设置', icon: markRaw(Lock) },
  { key: 'preference', label: '偏好设置', icon: markRaw(Setting) },
]

const tabNavRef = ref<HTMLElement>()
const tabRefs: Record<string, HTMLElement | null> = {}
const setTabRef = (k: string, el: any) => { tabRefs[k] = el as HTMLElement | null }
const indicatorStyle = ref({ left: '0px', width: '0px', opacity: '0' })

const updateIndicator = () => {
  const el = tabRefs[activeTab.value]
  if (!el || !tabNavRef.value) return
  const navRect = tabNavRef.value.getBoundingClientRect()
  const btnRect = el.getBoundingClientRect()
  indicatorStyle.value = {
    left: (btnRect.left - navRect.left) + 'px',
    width: btnRect.width + 'px',
    opacity: '1',
  }
}
watch(activeTab, () => nextTick(updateIndicator))
onMounted(() => nextTick(updateIndicator))

const roleLabel = computed(() => {
  const map: Record<string, string> = { admin: '超级管理员', user: '普通用户' }
  return map[userInfo.value?.role || 'user'] || '用户'
})

const heroDesc = computed(() => {
  return userInfo.value?.role === 'admin'
    ? '系统超级管理员，拥有所有功能的访问权限'
    : '系统普通用户，可使用基础问答和调研功能'
})

const triggerAvatarUpload = () => { avatarInputRef.value?.click() }
const onAvatarFileChange = async (e: Event) => {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  avatarPreview.value = URL.createObjectURL(file)
  avatarUploading.value = true
  try {
    await userApi.uploadAvatar(file)
    await userStore.fetchUserInfo()
    ElMessage.success('头像更新成功')
  } catch {
    avatarPreview.value = ''
    ElMessage.error('头像上传失败，请重试')
  } finally {
    avatarUploading.value = false
    if (avatarInputRef.value) avatarInputRef.value.value = ''
  }
}

const formatDate = (s?: string) => {
  if (!s) return '—'
  return new Date(s).toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\//g, '/')
}
const formatDateTime = (s?: string) => {
  if (!s) return '—'
  const d = new Date(s)
  return d.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}

const basicForm = reactive({ nickname: '', phone: '' })

onMounted(() => {
  if (userInfo.value) {
    basicForm.nickname = userInfo.value.nickname || ''
    basicForm.phone    = userInfo.value.phone || ''
  }
})

const saveBasicInfo = async () => {
  basicLoading.value = true
  try {
    await userApi.updateUserInfo(basicForm)
    await userStore.fetchUserInfo()
    ElMessage.success('信息更新成功')
  } catch {
    ElMessage.error('保存失败，请重试')
  } finally {
    basicLoading.value = false
  }
}

const scrollToForm = () => {
  activeTab.value = 'basic'
  setTimeout(() => {
    document.querySelector('.form-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, 100)
}

const stats = [
  {
    key: 'knowledge', label: '知识库数量', value: '32', suffix: '',
    trend: '较上月 +12%', trendUp: true,
    icon: markRaw(FolderOpened), iconBg: '#EEF2FF', iconColor: '#5B8FF9',
    spark: 'M2,18 L12,14 L22,16 L32,10 L42,12 L52,8 L62,6 L72,4 L78,5'
  },
  {
    key: 'agent', label: 'Agent 数量', value: '18', suffix: '',
    trend: '较上月 +8%', trendUp: true,
    icon: markRaw(MagicStick), iconBg: '#F3F0FF', iconColor: '#A66CFF',
    spark: 'M2,16 L12,18 L22,12 L32,14 L42,8 L52,10 L62,6 L72,8 L78,4'
  },
  {
    key: 'task', label: '调研任务数', value: '126', suffix: '',
    trend: '较上月 +23%', trendUp: true,
    icon: markRaw(Histogram), iconBg: '#E6FFFA', iconColor: '#10B981',
    spark: 'M2,20 L12,16 L22,18 L32,12 L42,14 L52,8 L62,10 L72,6 L78,3'
  },
  {
    key: 'duration', label: '累计使用时长', value: '99.8', suffix: '小时',
    trend: '较上月 +18%', trendUp: true,
    icon: markRaw(Timer), iconBg: '#FFF7E6', iconColor: '#FFB547',
    spark: 'M2,14 L12,16 L22,10 L32,12 L42,8 L52,6 L62,8 L72,5 L78,7'
  },
]

const loginHistory = [
  { time: '2026/06/03 10:23:45', device: 'Chrome / Windows', ip: '120.80.32.18', location: '中国 · 北京', isCurrent: true  },
  { time: '2026/06/02 22:15:33', device: 'Safari / iPhone',  ip: '120.80.32.18', location: '中国 · 上海', isCurrent: false },
  { time: '2026/06/01 09:08:12', device: 'Chrome / macOS',   ip: '183.66.10.4',  location: '中国 · 深圳', isCurrent: false },
  { time: '2026/05/30 18:42:07', device: 'Chrome / Windows', ip: '120.80.32.18', location: '中国 · 北京', isCurrent: false },
]
</script>

<style scoped>
/* ─────────────────────────────────────────────
   Page chrome
   ───────────────────────────────────────────── */
.profile-page {
  height: 100%;
  overflow-y: auto;
  padding: 22px 32px 56px;
  background: var(--bg-page);
}

/* Breadcrumb */
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 22px;
  font-size: 13px;
  color: var(--ink-3);
  font-weight: 500;
}
.crumb-item { transition: color 180ms var(--ease); }
.crumb-item.ghost { color: var(--ink-4); cursor: pointer; }
.crumb-item.ghost:hover { color: var(--ink-2); }
.crumb-sep { color: var(--ink-4); }

/* ─────────────────────────────────────────────
   HERO
   ───────────────────────────────────────────── */
.hero {
  position: relative;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 30px 32px;
  margin-bottom: 22px;
  overflow: hidden;
  box-shadow: var(--shadow-card);
}
.hero-decor {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.6;
}
.orb-1 { width: 280px; height: 280px; top: -80px; right: -40px; background: radial-gradient(circle, #C7BFFF 0%, transparent 70%); }
.orb-2 { width: 220px; height: 220px; top: 40px; right: 200px;  background: radial-gradient(circle, #E2D4FF 0%, transparent 70%); }
.orb-3 { width: 160px; height: 160px; top: 100px; right: -20px; background: radial-gradient(circle, #FFD5F1 0%, transparent 70%); }

.hero-grid {
  position: relative;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 28px;
  align-items: center;
}

.hero-avatar-wrap { cursor: pointer; position: relative; }
.hero-avatar {
  width: 132px;
  height: 132px;
  border-radius: 50%;
  background: linear-gradient(135deg, #8B7EFF 0%, #6B5AE6 55%, #4A3FBA 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-family: 'Manrope', sans-serif;
  font-weight: 700;
  position: relative;
  overflow: hidden;
  box-shadow:
    0 18px 40px rgba(107, 90, 230, 0.34),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}
.hero-avatar img { width: 100%; height: 100%; object-fit: cover; }
.avatar-letter {
  font-size: 56px;
  letter-spacing: -0.04em;
}
.avatar-mask {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  opacity: 0;
  transition: opacity 200ms var(--ease);
}
.hero-avatar-wrap:hover .avatar-mask { opacity: 1; }
.avatar-mask.uploading { opacity: 1; }
.avatar-mask.uploading .el-icon { animation: spin 1.2s linear infinite; }

.hero-info { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.hero-name {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 26px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.022em;
  line-height: 1.2;
}
.hero-row { display: flex; align-items: center; gap: 10px; }
.hero-handle {
  font-family: 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--ink-3);
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  background: linear-gradient(135deg, #F0EBFF, #E2D5FF);
  color: #6B5AE6;
  border: 1px solid #D4C2FF;
}
.hero-badge.user { background: var(--bg-subtle); color: var(--ink-3); border-color: var(--line); }

.hero-desc {
  font-size: 13.5px;
  color: var(--ink-2);
  margin-top: 2px;
  line-height: 1.6;
}

.hero-meta {
  display: flex;
  gap: 20px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.meta-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}
.meta-ic {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.meta-k { color: var(--ink-3); font-weight: 500; }
.meta-v { color: var(--ink-1); font-weight: 600; font-family: 'JetBrains Mono', monospace; }

.hero-cta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  color: var(--ink-1);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition);
  align-self: flex-start;
}
.hero-cta:hover {
  border-color: var(--brand);
  color: var(--brand);
  background: var(--brand-soft);
}

/* ─────────────────────────────────────────────
   STATS
   ───────────────────────────────────────────── */
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 22px;
}
.stat-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px 18px 14px;
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: var(--transition);
}
.stat-card:hover {
  border-color: var(--brand-soft-2);
  box-shadow: var(--shadow-md);
}
.stat-head { display: flex; align-items: center; gap: 10px; }
.stat-ic {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-label {
  font-size: 12.5px;
  color: var(--ink-3);
  font-weight: 500;
}
.stat-num {
  font-family: 'Manrope', sans-serif;
  font-size: 28px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.02em;
  line-height: 1.1;
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.stat-suffix {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-3);
  margin-left: 2px;
}
.stat-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.stat-trend {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  font-weight: 600;
}
.stat-trend.up   { color: var(--success-ink); }
.stat-trend.down { color: var(--danger-ink); }
.spark { width: 72px; height: 22px; opacity: 0.7; flex-shrink: 0; }

/* ─────────────────────────────────────────────
   TABS
   ───────────────────────────────────────────── */
.tabs {
  position: relative;
  display: flex;
  gap: 4px;
  padding: 0 4px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 22px;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 12px 16px;
  background: transparent;
  border: none;
  color: var(--ink-3);
  font-family: 'Manrope', sans-serif;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: color 180ms var(--ease);
  position: relative;
}
.tab:hover { color: var(--ink-1); }
.tab.active { color: var(--brand); }
.tab-bar {
  position: absolute;
  bottom: -1px;
  height: 2px;
  background: linear-gradient(90deg, var(--brand-hover), var(--brand));
  border-radius: 2px;
  transition: left 220ms var(--ease), width 220ms var(--ease);
}

/* ─────────────────────────────────────────────
   CONTENT — 两栏
   ───────────────────────────────────────────── */
.content {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 18px;
  margin-bottom: 22px;
}
.content.single { grid-template-columns: 1fr; }

.card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-card);
  padding: 22px 24px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.card-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.012em;
}
.card-sub {
  font-size: 12.5px;
  color: var(--ink-3);
  margin-top: 2px;
}

/* 表单 */
.profile-form { display: flex; flex-direction: column; gap: 16px; }
.field {
  display: grid;
  grid-template-columns: 160px 1fr;
  align-items: center;
  gap: 16px;
}
.field-lbl { display: flex; flex-direction: column; gap: 2px; }
.field-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-1);
}
.field-hint {
  font-size: 11.5px;
  color: var(--ink-3);
}
.field-input {
  height: 40px;
  padding: 0 14px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--line);
  background: var(--bg-surface);
  font-size: 13.5px;
  color: var(--ink-1);
  font-family: inherit;
  outline: none;
  transition: var(--transition);
}
.field-input::placeholder { color: var(--ink-4); }
.field-input:hover { border-color: var(--line-strong); }
.field-input:focus {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow);
}
.field-input.disabled,
.field-input:disabled {
  background: var(--bg-subtle);
  color: var(--ink-3);
  cursor: not-allowed;
}
.field-static {
  height: 40px;
  display: flex;
  align-items: center;
  padding: 0 14px;
  background: var(--bg-subtle);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: 13.5px;
  color: var(--ink-2);
}

.form-foot {
  margin-top: 6px;
  padding-top: 12px;
}
.save-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 22px;
  border-radius: var(--radius-sm);
  background: linear-gradient(180deg, var(--brand-hover), var(--brand));
  color: #fff;
  font-family: 'Manrope', sans-serif;
  font-weight: 700;
  font-size: 13.5px;
  letter-spacing: 0.01em;
  cursor: pointer;
  border: none;
  box-shadow: var(--shadow-brand);
  transition: var(--transition);
}
.save-btn:hover:not(:disabled) { filter: brightness(1.06); }
.save-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.loading-wrap { display: inline-flex; align-items: center; gap: 6px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* 安全列表 */
.account-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.acc-row {
  display: grid;
  grid-template-columns: 36px 1fr auto auto;
  gap: 12px;
  align-items: center;
  padding: 12px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: var(--transition);
}
.acc-row:hover { background: var(--bg-subtle); }
.acc-row.danger:hover { background: var(--danger-soft); }
.acc-ic {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.acc-body { min-width: 0; }
.acc-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-1);
}
.acc-row.danger .acc-title { color: var(--danger-ink); }
.acc-sub {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: 2px;
}
.acc-flag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
}
.acc-flag.warn { background: var(--warning-soft); color: var(--warning-ink); }
.acc-arrow { color: var(--ink-4); transition: transform 180ms var(--ease); }
.acc-row:hover .acc-arrow { color: var(--brand); transform: translateX(2px); }
.acc-row.danger:hover .acc-arrow { color: var(--danger); }

/* 占位 */
.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 60px 24px;
  text-align: center;
  gap: 12px;
}
.ph-ic {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--brand-soft);
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}
.placeholder h3 {
  font-family: 'Manrope', sans-serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-1);
}
.placeholder p {
  font-size: 13px;
  color: var(--ink-3);
  max-width: 380px;
  line-height: 1.7;
}

/* ─────────────────────────────────────────────
   LOGIN HISTORY
   ───────────────────────────────────────────── */
.login-card { padding: 22px 24px 18px; }
.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--brand);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-xs);
  transition: var(--transition);
}
.link-btn:hover { background: var(--brand-soft); }

.login-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.login-table th {
  text-align: left;
  padding: 10px 16px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-3);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--line);
}
.login-table th:first-child { border-top-left-radius: var(--radius-sm); }
.login-table th:last-child  { border-top-right-radius: var(--radius-sm); }
.login-table td {
  padding: 14px 16px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--ink-2);
}
.login-table tr:last-child td { border-bottom: none; }
.login-table tr:hover td { background: rgba(61, 90, 254, 0.025); }
.login-table .mono { font-family: 'JetBrains Mono', monospace; font-size: 12.5px; color: var(--ink-1); }

.dev { display: inline-flex; align-items: center; gap: 8px; }
.dev-ic {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--bg-subtle);
  color: var(--ink-2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.status {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  font-size: 11px;
  font-weight: 700;
  border-radius: var(--radius-pill);
}
.status.current { background: var(--brand-soft); color: var(--brand-ink); }
.status.normal  { background: var(--success-soft); color: var(--success-ink); }

/* ─────────────────────────────────────────────
   Responsive
   ───────────────────────────────────────────── */
@media (max-width: 1100px) {
  .stats { grid-template-columns: repeat(2, 1fr); }
  .content { grid-template-columns: 1fr; }
}
@media (max-width: 760px) {
  .stats { grid-template-columns: 1fr; }
  .hero-grid { grid-template-columns: 1fr; text-align: center; }
  .hero-avatar { margin: 0 auto; width: 100px; height: 100px; }
  .avatar-letter { font-size: 40px; }
  .field { grid-template-columns: 1fr; gap: 6px; }
  .login-table { display: block; overflow-x: auto; }
}
</style>
