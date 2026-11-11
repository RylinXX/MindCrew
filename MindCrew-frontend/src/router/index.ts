import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // 登录页
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/auth/LoginView.vue'),
      meta: { requiresAuth: false }
    },
    // 注册页
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/auth/RegisterView.vue'),
      meta: { requiresAuth: false }
    },
    // 找回密码
    {
      path: '/forgot-password',
      name: 'ForgotPassword',
      component: () => import('@/views/auth/ForgotPasswordView.vue'),
      meta: { requiresAuth: false }
    },
    // 客服中心 · 对外客服页（公开，独立全屏布局，不套 MainLayout）
    {
      path: '/cs',
      name: 'CsPortal',
      component: () => import('@/views/cs/CsPortalView.vue'),
      meta: { requiresAuth: false, title: '在线客服' }
    },
    // 主布局（需要认证）
    {
      path: '/',
      component: () => import('@/layout/MainLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          redirect: '/chat'
        },
        // 智能问答
        {
          path: 'chat',
          name: 'Chat',
          component: () => import('@/views/chat/ChatView.vue'),
          meta: { title: '智能问答', icon: 'ChatDotRound' }
        },
        // Multi-Agent 调研引擎
        {
          path: 'crew',
          name: 'Crew',
          component: () => import('@/views/crew/CrewView.vue'),
          meta: { title: 'Agent 调研', icon: 'MagicStick' }
        },
        // 教练模式 · 任务 9
        {
          path: 'coach',
          name: 'Coach',
          component: () => import('@/views/coach/CoachView.vue'),
          meta: { title: '教练模式', icon: 'Trophy' }
        },
        // 语音通话 · 任务 14
        {
          path: 'voice-call',
          name: 'VoiceCall',
          component: () => import('@/views/voice/VoiceCallView.vue'),
          meta: { title: '语音通话', icon: 'PhoneFilled' }
        },
        // Trace Replay · 推理链回放
        {
          path: 'crew/replay/:taskId',
          name: 'CrewReplay',
          component: () => import('@/views/crew/CrewReplayView.vue'),
          meta: { title: '推理链回放', icon: 'VideoCamera' }
        },
        // Agent Communication Graph · 通信图谱
        {
          path: 'crew/graph/:taskId',
          name: 'CrewGraph',
          component: () => import('@/views/crew/CrewGraphView.vue'),
          meta: { title: 'Agent 通信图谱', icon: 'Share' }
        },
        {
          path: 'chat/:id',
          name: 'ChatDetail',
          component: () => import('@/views/chat/ChatView.vue'),
          meta: { title: '智能问答', icon: 'ChatDotRound' }
        },
        // 知识库管理
        {
          path: 'knowledge',
          name: 'Knowledge',
          component: () => import('@/views/knowledge/KnowledgeView.vue'),
          meta: { title: '知识库', icon: 'FolderOpened', requiresAdmin: true }
        },
        // MCP 控制台
        {
          path: 'mcp',
          name: 'Mcp',
          component: () => import('@/views/mcp/McpView.vue'),
          meta: { title: 'MCP 控制台', icon: 'Grid', requiresAdmin: true }
        },
        // 数据统计
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/dashboard/DashboardView.vue'),
          meta: { title: '数据大屏', icon: 'DataAnalysis', requiresAdmin: true }
        },
        // 用户管理
        {
          path: 'users',
          name: 'Users',
          component: () => import('@/views/user/UserManageView.vue'),
          meta: { title: '用户管理', icon: 'User', requiresAdmin: true }
        },
        // AI 配置中心
        {
          path: 'ai-config',
          name: 'AiConfig',
          component: () => import('@/views/admin/AiConfigView.vue'),
          meta: { title: 'AI配置中心', icon: 'SetUp', requiresAdmin: true }
        },
        // Soul 人格管理
        {
          path: 'persona',
          name: 'Persona',
          component: () => import('@/views/admin/PersonaView.vue'),
          meta: { title: 'Soul 人格', icon: 'StarFilled', requiresAdmin: true }
        },
        // LLM Provider · 跨厂商模型切换
        {
          path: 'llm-provider',
          name: 'LlmProvider',
          component: () => import('@/views/admin/LlmProviderView.vue'),
          meta: { title: '大模型 Provider', icon: 'Connection', requiresAdmin: true }
        },
        // 反馈审核 · 任务 6
        {
          path: 'feedback-review',
          name: 'FeedbackReview',
          component: () => import('@/views/admin/FeedbackReviewView.vue'),
          meta: { title: '反馈审核', icon: 'ChatLineSquare', requiresAdmin: true }
        },
        // Golden Pair 库 · 任务 6
        {
          path: 'golden-pair',
          name: 'GoldenPair',
          component: () => import('@/views/admin/GoldenPairView.vue'),
          meta: { title: 'Golden Pair 库', icon: 'CircleCheckFilled', requiresAdmin: true }
        },
        // 历史对话搜索 · 任务 13.5
        {
          path: 'conv-search',
          name: 'ConvSearch',
          component: () => import('@/views/admin/ConvSearchView.vue'),
          meta: { title: '历史对话搜索', icon: 'Search' }
        },
        // 组织与职位 · 任务 7
        {
          path: 'org',
          name: 'Org',
          component: () => import('@/views/admin/OrgView.vue'),
          meta: { title: '组织与职位', icon: 'OfficeBuilding', requiresAdmin: true }
        },
        // 教练学习统计 · 任务 9.4
        {
          path: 'coach-stats',
          name: 'CoachStats',
          component: () => import('@/views/admin/CoachStatsView.vue'),
          meta: { title: '教练学习统计', icon: 'Trophy', requiresAdmin: true }
        },
        // 用户用量详情 · 任务 13.6
        {
          path: 'user-usage/:id',
          name: 'UserUsageDetail',
          component: () => import('@/views/admin/UserUsageDetailView.vue'),
          meta: { title: '用户用量详情', icon: 'DataLine', requiresAdmin: true }
        },
        // 审计日志 · 任务 12.1
        {
          path: 'audit-log',
          name: 'AuditLog',
          component: () => import('@/views/admin/AuditLogView.vue'),
          meta: { title: '审计日志', icon: 'Document', requiresAdmin: true }
        },
        // PII 脱敏配置 · 任务 12.2
        {
          path: 'pii-config',
          name: 'PiiConfig',
          component: () => import('@/views/admin/PiiConfigView.vue'),
          meta: { title: 'PII 脱敏', icon: 'Lock', requiresAdmin: true }
        },
        // 个人中心
        {
          path: 'profile',
          name: 'Profile',
          component: () => import('@/views/user/ProfileView.vue'),
          meta: { title: '个人中心', icon: 'Setting' }
        },
        // 客服中心 · 客服工作台（登录即可）
        {
          path: 'cs-console',
          name: 'CsConsole',
          component: () => import('@/views/cs/CsConsoleView.vue'),
          meta: { title: '客服工作台', icon: 'Headset' }
        },
        // 客服中心 · 配置（管理员）
        {
          path: 'cs-settings',
          name: 'CsSettings',
          component: () => import('@/views/admin/CsSettingsView.vue'),
          meta: { title: '客服中心配置', icon: 'SetUp', requiresAdmin: true }
        },
        // 检索分析（管理员）
        {
          path: 'retrieval-analytics',
          name: 'RetrievalAnalytics',
          component: () => import('@/views/admin/RetrievalAnalyticsView.vue'),
          meta: { title: '检索分析', icon: 'DataAnalysis', requiresAdmin: true }
        },
        // 数字员工 · 交付物（登录即可）
        {
          path: 'deliverables',
          name: 'Deliverables',
          component: () => import('@/views/office/DeliverableView.vue'),
          meta: { title: '数字员工', icon: 'Files' }
        },
        // AI 问数 · 指标问答（登录即可）
        {
          path: 'data-qa',
          name: 'DataQa',
          component: () => import('@/views/dataqa/DataQaView.vue'),
          meta: { title: 'AI 问数', icon: 'DataLine' }
        }
      ]
    },
    // 404
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

// 全局路由守卫
router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()

  if (to.meta.requiresAuth === false) {
    // 已登录访问登录页，跳转首页
    if (userStore.isLoggedIn && (to.path === '/login' || to.path === '/register')) {
      return next('/')
    }
    return next()
  }

  // 未登录跳转登录页
  if (!userStore.isLoggedIn) {
    return next(`/login?redirect=${to.path}`)
  }

  // 加载用户信息
  if (!userStore.userInfo) {
    try {
      await userStore.fetchUserInfo()
    } catch {
      userStore.logout()
      return next('/login')
    }
  }

  next()
})

export default router
