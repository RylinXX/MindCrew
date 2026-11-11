<template>
  <div class="main-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: isCollapsed }">
      <!-- Logo -->
      <div class="sidebar-logo">
        <div class="logo-icon">
          <MindCrewIcon :size="22" color="#fff" accent-color="#38bdf8" />
        </div>
        <transition name="fade">
          <span v-if="!isCollapsed" class="logo-text">MindCrew</span>
        </transition>
      </div>

      <!-- 导航菜单 · 分组显示（可折叠） -->
      <nav class="sidebar-nav">
        <template v-for="(group, gi) in menuGroups" :key="gi">
          <div v-if="groupHasVisibleItems(group)" class="nav-group">
            <!-- 分组标题（展开态可点击折叠/展开） -->
            <button
              v-if="!isCollapsed"
              class="nav-group-header"
              :class="{ 'is-current': activeGroupIndex === gi }"
              type="button"
              @click="toggleGroup(gi)"
            >
              <span class="group-label">{{ group.title }}</span>
              <span class="group-tail">
                <el-icon
                  class="group-arrow"
                  :class="{ collapsed: !isGroupExpanded(gi) }"
                  size="11"
                ><ArrowDown /></el-icon>
              </span>
            </button>
            <!-- 折叠态分组之间用一条 1px 分隔 -->
            <div
              v-else-if="isCollapsed && gi > 0"
              class="nav-group-divider"
            ></div>

            <!-- 组内项（折叠动画） -->
            <div
              class="nav-group-items"
              :class="{ 'is-collapsed': !isCollapsed && !isGroupExpanded(gi) }"
            >
              <router-link
                v-for="item in group.items"
                :key="item.path"
                :to="item.path"
                class="nav-item"
                :class="{ active: isActive(item.path) }"
                :style="isActive(item.path) ? { '--nav-color': item.color } : {}"
                v-show="canAccess(item)"
              >
                <span class="nav-icon-wrap" :style="{ '--icon-color': item.color }">
                  <el-icon size="17"><component :is="item.icon" /></el-icon>
                </span>
                <span v-if="!isCollapsed" class="nav-label">{{ item.label }}</span>
              </router-link>
            </div>
          </div>
        </template>
      </nav>

      <!-- 分隔线 -->
      <div class="sidebar-divider" v-if="!isCollapsed"></div>

      <!-- 用户信息 -->
      <div class="sidebar-user" :class="{ 'collapsed-user': isCollapsed }">
        <el-avatar :size="32" :src="userStore.userInfo?.avatar" class="user-avatar-small">
          {{ (userStore.userInfo?.nickname || userStore.userInfo?.username || 'U').charAt(0).toUpperCase() }}
        </el-avatar>
        <div v-if="!isCollapsed" class="user-meta">
          <div class="user-name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</div>
          <span class="user-role-marker" :class="userStore.isAdmin ? 'admin' : 'user'">
            {{ userStore.isAdmin ? 'Admin' : 'User' }}
          </span>
        </div>
        <el-tooltip v-if="!isCollapsed" content="退出登录" placement="right">
          <button class="logout-btn" @click="handleLogout">
            <el-icon size="15"><SwitchButton /></el-icon>
          </button>
        </el-tooltip>
      </div>

      <!-- 折叠按钮 -->
      <button class="collapse-btn" @click="isCollapsed = !isCollapsed">
        <el-icon size="13"><component :is="isCollapsed ? 'DArrowRight' : 'DArrowLeft'" /></el-icon>
      </button>
    </aside>

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 顶部栏 -->
      <header class="top-header">
        <div class="header-left">
          <div class="page-indicator">
            <div class="indicator-dot" :style="{ background: currentMenu?.color || '#38bdf8' }"></div>
            <span class="page-title">{{ currentTitle }}</span>
          </div>
        </div>
        <div class="header-right">
          <div class="header-pills">
            <span class="status-label">
              <span class="status-ring"></span>系统正常
            </span>
          </div>
          <el-dropdown trigger="click" @command="handleCommand">
            <div class="header-user">
              <el-avatar :size="30" :src="userStore.userInfo?.avatar">
                {{ (userStore.userInfo?.nickname || 'U').charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="header-username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
              <el-icon size="12" color="#8B95AB"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon> 个人中心
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 页面内容 -->
      <div class="page-body">
        <router-view v-slot="{ Component }">
          <transition name="slide-up" mode="out-in">
            <component :is="Component" :key="$route.path" />
          </transition>
        </router-view>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import MindCrewIcon from '@/components/MindCrewIcon.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isCollapsed = ref(localStorage.getItem('sidebar-collapsed') === '1')

// 持久化整体折叠状态
watch(isCollapsed, v => {
  localStorage.setItem('sidebar-collapsed', v ? '1' : '0')
})

// 菜单按业务分组 · 5 个核心组 + 个人
interface MenuItem {
  path: string
  label: string
  icon: string
  color: string
  requiresAdmin?: boolean
}
interface MenuGroup { title: string; items: MenuItem[] }
const menuGroups: MenuGroup[] = [
  {
    title: '工作台',
    items: [
      { path: '/chat',      label: '智能问答',    icon: 'ChatDotRound', color: '#3D5AFE' },
      { path: '/crew',      label: 'Agent 调研',  icon: 'MagicStick',   color: '#7C3AED' },
      { path: '/coach',      label: '教练模式',    icon: 'Trophy',       color: '#7C3AED' },
      { path: '/voice-call', label: '语音通话',    icon: 'PhoneFilled',  color: '#10B981' },
      { path: '/knowledge',  label: '知识库',      icon: 'FolderOpened', color: '#0EA5E9', requiresAdmin: true },
      { path: '/data-qa',    label: 'AI 问数',     icon: 'DataLine',     color: '#0EA5E9' },
      { path: '/deliverables', label: '数字员工',  icon: 'Files',        color: '#10B981' },
    ],
  },
  {
    title: '反馈与质量',     // 任务 6 校正反哺闭环
    items: [
      { path: '/feedback-review', label: '反馈审核',        icon: 'ChatLineSquare',    color: '#F59E0B', requiresAdmin: true },
      { path: '/golden-pair',     label: 'Golden Pair 库', icon: 'CircleCheckFilled', color: '#34D399', requiresAdmin: true },
      { path: '/conv-search',     label: '历史对话搜索',    icon: 'Search',            color: '#8B5CF6' },
    ],
  },
  {
    title: '组织与权限',     // 任务 7 职位独立 KB
    items: [
      { path: '/users',       label: '用户管理',    icon: 'UserFilled',    color: '#EF4444', requiresAdmin: true },
      { path: '/org',         label: '组织与职位',  icon: 'OfficeBuilding', color: '#7C3AED', requiresAdmin: true },
      { path: '/coach-stats', label: '教练学习统计', icon: 'Trophy',         color: '#7C3AED', requiresAdmin: true },
    ],
  },
  {
    title: 'AI 模型配置',
    items: [
      { path: '/ai-config',    label: 'AI 配置',         icon: 'SetUp',      color: '#A78BFA', requiresAdmin: true },
      { path: '/persona',      label: 'Soul 人格',        icon: 'StarFilled', color: '#EC4899', requiresAdmin: true },
      { path: '/llm-provider', label: '大模型 Provider', icon: 'Connection', color: '#06B6D4', requiresAdmin: true },
    ],
  },
  {
    title: '合规与安全',     // 任务 12 审计 + 脱敏
    items: [
      { path: '/audit-log',  label: '审计日志', icon: 'Document', color: '#F59E0B', requiresAdmin: true },
      { path: '/pii-config', label: 'PII 脱敏', icon: 'Lock',     color: '#EF4444', requiresAdmin: true },
    ],
  },
  {
    title: '运维与监控',
    items: [
      { path: '/mcp',       label: 'MCP 控制台', icon: 'Grid',         color: '#10B981', requiresAdmin: true },
      { path: '/dashboard', label: '数据大屏',   icon: 'DataAnalysis', color: '#F59E0B', requiresAdmin: true },
      { path: '/retrieval-analytics', label: '检索分析', icon: 'TrendCharts', color: '#8B5CF6', requiresAdmin: true },
    ],
  },
  {
    title: '客服中心',
    items: [
      { path: '/cs-console',  label: '客服工作台',   icon: 'Headset', color: '#F43F5E' },
      { path: '/cs-settings', label: '客服中心配置', icon: 'Tools',   color: '#F97316', requiresAdmin: true },
    ],
  },
  {
    title: '个人',
    items: [
      { path: '/profile', label: '个人中心', icon: 'Setting', color: '#64748B' },
    ],
  },
]

// 平铺所有菜单项，用于 currentMenu computed
const menuItems = menuGroups.flatMap(g => g.items)

const canAccess = (item: MenuItem) => item.requiresAdmin ? userStore.isAdmin : true

// 该分组是否至少有一个用户可访问的项（用于隐藏空组）
const groupHasVisibleItems = (group: MenuGroup) =>
  group.items.some(canAccess)
const isActive  = (path: string) => route.path.startsWith(path)

const currentMenu = computed(() => menuItems.find(m => route.path.startsWith(m.path)))
const currentTitle = computed(() => currentMenu.value?.label || 'MindCrew')

// ─── 分组折叠状态 ───
// localStorage 保存折叠的组 index 数组；未列出的默认展开
const collapsedGroups = ref<Set<number>>(
  new Set(JSON.parse(localStorage.getItem('sidebar-collapsed-groups') || '[]'))
)

const isGroupExpanded = (gi: number) => !collapsedGroups.value.has(gi)

const activeGroupIndex = computed(() =>
  menuGroups.findIndex(g => g.items.some(it => isActive(it.path)))
)

const toggleGroup = (gi: number) => {
  const next = new Set(collapsedGroups.value)
  if (next.has(gi)) next.delete(gi)
  else next.add(gi)
  collapsedGroups.value = next
  localStorage.setItem('sidebar-collapsed-groups', JSON.stringify([...next]))
}

// 用户切到某页 → 自动展开它所属的组
watch(activeGroupIndex, (gi) => {
  if (gi < 0) return
  if (collapsedGroups.value.has(gi)) {
    const next = new Set(collapsedGroups.value)
    next.delete(gi)
    collapsedGroups.value = next
    localStorage.setItem('sidebar-collapsed-groups', JSON.stringify([...next]))
  }
}, { immediate: true })

const handleCommand = async (command: string) => {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'logout') {
    handleLogout()
  }
}

const handleLogout = async () => {
  await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.main-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--bg-page);
}

/* ─── 侧边栏 ─── */
.sidebar {
  width: 264px;                       /* 240 → 264，告别紧凑感 */
  background: var(--sidebar-bg);
  display: flex;
  flex-direction: column;
  transition: width 240ms cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  flex-shrink: 0;
  border-right: 1px solid var(--line);
}
.sidebar.collapsed { width: 72px; }
.sidebar.collapsed .nav-item {
  justify-content: center;
  padding: 11px 0;
}
.sidebar.collapsed .nav-item::before {
  left: 0; width: 3px;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 20px;
  border-bottom: 1px solid var(--line-soft);
  overflow: hidden;
  min-height: var(--header-height);
}
.logo-icon {
  width: 36px;
  height: 36px;
  background: linear-gradient(135deg, var(--brand-hover) 0%, var(--brand) 60%, var(--brand-active) 100%);
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 6px 16px rgba(61, 90, 254, 0.32), inset 0 1px 0 rgba(255,255,255,0.22);
  position: relative;
  overflow: hidden;
}
.logo-icon::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(140deg, rgba(255,255,255,0.18), transparent 50%);
  pointer-events: none;
}
.logo-text {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 18px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.02em;
  white-space: nowrap;
}

.sidebar-nav {
  flex: 1;
  padding: 12px 10px 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
  overflow-x: hidden;
  /* 细滚动条 */
  scrollbar-width: thin;
}
.sidebar-nav::-webkit-scrollbar { width: 4px; }
.sidebar-nav::-webkit-scrollbar-thumb { background: var(--line); border-radius: 2px; }

/* 分组容器 */
.nav-group {
  display: flex;
  flex-direction: column;
  margin-bottom: 4px;
}

/* 分组标题（展开态 · 可点击） */
.nav-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 14px 12px 8px;
  margin-top: 4px;
  background: none;
  border: none;
  cursor: pointer;
  font-family: inherit;
  font-size: 10.5px;
  font-weight: 700;
  color: var(--ink-4);
  text-transform: uppercase;
  letter-spacing: 0.1em;
  user-select: none;
  border-radius: 6px;
  transition: color 160ms ease, background 160ms ease;
}
.nav-group-header:hover {
  color: var(--ink-2);
}
.nav-group-header:hover .group-arrow {
  color: var(--ink-2);
}
.nav-group-header.is-current {
  color: var(--brand);
}
.nav-group-header.is-current .group-label::before {
  content: '';
  display: inline-block;
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--brand);
  margin-right: 7px;
  vertical-align: 2px;
  box-shadow: 0 0 0 3px var(--brand-soft);
}

.group-label { flex: 1; text-align: left; }
.group-tail {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.group-arrow {
  color: var(--ink-4);
  transition: transform 200ms cubic-bezier(0.4, 0, 0.2, 1), color 160ms ease;
}
.group-arrow.collapsed { transform: rotate(-90deg); }

/* 折叠态分组分隔线 */
.nav-group-divider {
  height: 1px;
  background: var(--line-soft);
  margin: 10px 14px;
  border: none;
}

/* 分组内项目（含折叠动画） */
.nav-group-items {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 800px;
  overflow: hidden;
  transition: max-height 280ms cubic-bezier(0.4, 0, 0.2, 1),
              opacity 200ms ease,
              margin 200ms ease;
  opacity: 1;
}
.nav-group-items.is-collapsed {
  max-height: 0;
  opacity: 0;
  margin-top: -4px;
  pointer-events: none;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;            /* 更舒服的留白 */
  border-radius: 8px;
  color: var(--ink-2);
  text-decoration: none;
  transition: background 160ms ease, color 160ms ease;
  white-space: nowrap;
  overflow: hidden;
  position: relative;
  --nav-color: var(--brand);
}
.nav-item:hover {
  background: var(--bg-hover);
  color: var(--ink-1);
}
.nav-item.active {
  background: linear-gradient(90deg, var(--brand-soft) 0%, transparent 100%);
  color: var(--nav-color, var(--brand));
  font-weight: 600;
}
.nav-item.active::before {
  content: '';
  position: absolute;
  left: 0; top: 20%; bottom: 20%;
  width: 3px;
  background: var(--nav-color, var(--brand));
  border-radius: 0 3px 3px 0;
}

/* icon 容器：active 时着色 */
.nav-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  color: var(--ink-3);
  transition: color 160ms ease;
}
.nav-item:hover .nav-icon-wrap { color: var(--ink-1); }
.nav-item.active .nav-icon-wrap { color: var(--icon-color, var(--brand)); }

.nav-label { font-size: 13.5px; font-weight: 500; flex: 1; }
.nav-item.active .nav-label { font-weight: 600; }

.sidebar-divider {
  display: none;        /* user 区已有顶部边线，多余的不要 */
}

.sidebar-user {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 14px 16px 18px;
  overflow: hidden;
  border-top: 1px solid var(--line-soft);
  background: linear-gradient(180deg, transparent 0%, var(--bg-hover) 200%);
}
.sidebar-user.collapsed-user { justify-content: center; padding: 14px 0 18px; }
.user-meta { flex: 1; min-width: 0; }
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.2;
}
.user-role-marker {
  display: inline-block;
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  margin-top: 4px;
  padding: 1px 0;
  border-radius: 2px;
  text-transform: uppercase;
  /* Subtle bottom border instead of full pill — like an editorial label */
  border-bottom: 1.5px solid transparent;
  transition: var(--transition);
}
.user-role-marker.admin {
  color: var(--ink-2);
  border-bottom-color: var(--ink-4);
}
.user-role-marker.user {
  color: var(--ink-3);
  border-bottom-color: var(--line-strong);
}

.logout-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--ink-4);
  display: flex;
  align-items: center;
  padding: 5px;
  border-radius: 6px;
  transition: var(--transition);
}
.logout-btn:hover {
  color: var(--ink-2);
  background: var(--bg-subtle);
}

.collapse-btn {
  position: absolute;
  bottom: 20px;
  right: -12px;
  width: 24px;
  height: 24px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--ink-3);
  transition: var(--transition);
  z-index: 10;
  box-shadow: var(--shadow-sm);
}
.collapse-btn:hover {
  color: var(--brand);
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-sm);
}

/* ─── 主内容 ─── */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-page);
}

.top-header {
  height: var(--header-height);
  background: var(--bg-surface);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}

.header-left { display: flex; align-items: center; }
.page-indicator { display: flex; align-items: center; gap: 10px; }
.indicator-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
  box-shadow: 0 0 0 3px rgba(61, 90, 254, 0.10);
}
.page-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.015em;
}

.header-right { display: flex; align-items: center; gap: 16px; }
.header-pills { display: flex; gap: 8px; }
.status-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  color: var(--ink-3);
  font-weight: 500;
  letter-spacing: 0.02em;
  padding: 2px 2px;
}
.status-ring {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.14);
  flex-shrink: 0;
}

.header-user {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 6px 12px 6px 6px;
  border-radius: var(--radius-pill);
  transition: var(--transition);
  border: 1px solid transparent;
}
.header-user:hover {
  background: var(--bg-surface);
  border-color: var(--line);
  box-shadow: var(--shadow-sm);
}
.header-username { font-size: 13px; color: var(--ink-1); font-weight: 600; }

.page-body { flex: 1; min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.page-body > * { flex: 1; min-height: 0; }

/* 动画 */
.fade-enter-active, .fade-leave-active { transition: opacity 0.15s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
