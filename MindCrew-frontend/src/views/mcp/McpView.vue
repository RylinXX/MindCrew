<template>
  <div class="mcp-page page-container">
    <!-- 页头 -->
    <div class="mcp-header">
      <div>
        <h2 class="page-h2">MCP <span class="gradient-text">工具控制台</span></h2>
        <p class="page-desc">管理 Agent 可调用的外部工具集成，监控调用状态与性能</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="loadStats" :loading="loading" circle />
        <el-tag type="success" effect="light" size="large">
          <span class="online-dot"></span>
          {{ enabledCount }} 个工具已启用
        </el-tag>
      </div>
    </div>

    <!-- 工具卡片 -->
    <div class="tools-grid" v-loading="loading">
      <div
        v-for="tool in tools"
        :key="tool.toolName"
        class="tool-card"
        :class="{ enabled: tool.enabled, disabled: !tool.enabled }"
      >
        <!-- 顶部 -->
        <div class="tool-header">
          <div class="tool-icon" :style="{ background: tool.bg, border: `1px solid ${tool.border}` }">
            <component :is="tool.svgIcon" class="tool-svg" :style="{ color: tool.color }" />
          </div>
          <div class="tool-meta">
            <div class="tool-name">{{ tool.name }}</div>
            <div class="tool-type">{{ tool.type }}</div>
          </div>
          <el-switch
            v-model="tool.enabled"
            :active-color="tool.color"
            inactive-color="#1e293b"
            @change="(v: boolean) => onToggle(tool, v)"
          />
        </div>

        <!-- 描述 -->
        <div class="tool-desc">{{ tool.description }}</div>

        <!-- 能力标签 -->
        <div class="tool-caps">
          <span v-for="cap in tool.caps" :key="cap" class="cap-tag">{{ cap }}</span>
        </div>

        <!-- 性能指标 -->
        <div class="tool-metrics">
          <div class="metric-item">
            <div class="metric-val">{{ formatNum(tool.calls) }}</div>
            <div class="metric-key">调用次数</div>
          </div>
          <div class="metric-sep"></div>
          <div class="metric-item">
            <div class="metric-val" :class="getLatencyClass(tool.avgLatency)">
              {{ tool.avgLatency }}ms
            </div>
            <div class="metric-key">平均延迟</div>
          </div>
          <div class="metric-sep"></div>
          <div class="metric-item">
            <div class="metric-val" style="color:#34d399">--</div>
            <div class="metric-key">成功率</div>
          </div>
        </div>

        <!-- 测试按钮 -->
        <div class="tool-footer">
          <el-button
            size="small"
            :disabled="!tool.enabled"
            @click="testTool(tool)"
            :loading="tool.testing"
          >
            <el-icon><VideoPlay /></el-icon>
            测试工具
          </el-button>
          <span class="tool-status-text" :class="tool.enabled ? 'enabled' : 'disabled'">
            {{ tool.enabled ? '● 运行中' : '○ 已停用' }}
          </span>
        </div>
      </div>
    </div>

    <!-- 调用日志 -->
    <el-card class="log-card">
      <template #header>
        <div class="card-head">
          <span style="font-weight:600;color:var(--ink-1)">最近调用日志</span>
          <el-button size="small" text @click="clearLogs">清空</el-button>
        </div>
      </template>
      <div class="log-list" v-if="callLogs.length">
        <div v-for="(log, i) in callLogs" :key="i" class="log-item">
          <span class="log-time">{{ log.time }}</span>
          <span class="log-tool" :style="{ color: getToolColor(log.tool) }">{{ log.tool }}</span>
          <span class="log-latency">{{ log.latency }}ms</span>
          <span class="log-status" :class="log.success ? 'ok' : 'err'">
            {{ log.success ? '✓' : '✗' }}
          </span>
        </div>
      </div>
      <div v-else class="log-empty">暂无调用记录</div>
    </el-card>

    <!-- 测试弹窗 -->
    <el-dialog v-model="testDialogVisible" :title="`测试：${currentTool?.name}`" width="520px">

      <div v-if="testResult" class="test-result">
        <div class="test-result-header">
          <el-tag :type="testResult.success ? 'success' : 'danger'" size="small">
            {{ testResult.success ? '调用成功' : '调用失败' }}
          </el-tag>
          <span style="font-size:12px;color:#64748b">耗时 {{ testResult.latency }}ms</span>
        </div>
        <pre class="test-result-body">{{ testResult.output }}</pre>
      </div>
      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testRunning" @click="runTest">
          运行测试
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import { mcpApi } from '@/api/mcp'

interface Tool {
  dbId: number
  toolName: string
  name: string
  type: string
  description: string
  caps: string[]
  color: string; bg: string; border: string
  svgIcon: any
  enabled: boolean
  testing: boolean
  calls: number
  avgLatency: number
}

const loading = ref(false)
const testDialogVisible = ref(false)
const currentTool = ref<Tool | null>(null)
const testResult  = ref<any>(null)
const testRunning = ref(false)

const DocSearchSvg = {
  render: () => h('svg', { viewBox: '0 0 24 24', fill: 'currentColor', width: '20', height: '20' }, [
    h('path', { d: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm4 18H6V4h7v5h5v11zM8 15h8v2H8v-2zm0-4h8v2H8v-2z' })
  ])
}
const KeywordSvg = {
  render: () => h('svg', { viewBox: '0 0 24 24', fill: 'currentColor', width: '20', height: '20' }, [
    h('path', { d: 'M9.5 3A6.5 6.5 0 0 1 16 9.5c0 1.61-.59 3.09-1.56 4.23l.27.27H16l4.5 4.5-1.5 1.5L15 15.5v-1.29l-.27-.27A6.5 6.5 0 1 1 9.5 3zm0 2C7 5 5 7 5 9.5S7 14 9.5 14 14 12 14 9.5 12 5 9.5 5z' })
  ])
}
const WebSearchSvg = {
  render: () => h('svg', { viewBox: '0 0 24 24', fill: 'currentColor', width: '20', height: '20' }, [
    h('path', { d: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z' })
  ])
}
const MemorySvg = {
  render: () => h('svg', { viewBox: '0 0 24 24', fill: 'currentColor', width: '20', height: '20' }, [
    h('path', { d: 'M12 3C7.03 3 3 7.03 3 12s4.03 9 9 9 9-4.03 9-9-4.03-9-9-9zm0 16c-3.86 0-7-3.14-7-7s3.14-7 7-7 7 3.14 7 7-3.14 7-7 7zm.5-11h-1v5.25l4.5 2.67.75-1.23-3.75-2.22V8z' })
  ])
}

const TOOL_DISPLAY: Record<string, { name: string; type: string; description: string; caps: string[]; color: string; bg: string; border: string; svgIcon: any }> = {
  doc_search: {
    name: 'DocSearch', type: 'RAG 检索工具',
    description: '在知识库中进行语义向量检索，结合 BM25 关键词匹配，通过 RRF 融合与 Cross-Encoder 重排序返回最相关文档片段',
    caps: ['向量检索', 'BM25', 'RRF融合', '重排序'],
    color: '#38bdf8', bg: 'rgba(56,189,248,0.1)', border: 'rgba(56,189,248,0.2)', svgIcon: DocSearchSvg,
  },
  keyword_search: {
    name: 'KeywordSearch', type: '关键词搜索工具',
    description: '基于 BM25 算法进行纯关键词全文检索，适合精确匹配特定术语或专有名词的查询场景',
    caps: ['全文索引', 'BM25', '精确匹配'],
    color: '#818cf8', bg: 'rgba(129,140,248,0.1)', border: 'rgba(129,140,248,0.2)', svgIcon: KeywordSvg,
  },
  web_search: {
    name: 'WebSearch', type: '互联网搜索工具',
    description: '调用阿里云 OpenSearch 获取实时网络信息，补充知识库中未涵盖的最新内容',
    caps: ['实时数据', '网络搜索', 'OpenSearch'],
    color: '#34d399', bg: 'rgba(52,211,153,0.1)', border: 'rgba(52,211,153,0.2)', svgIcon: WebSearchSvg,
  },
  recall_memory: {
    name: 'RecallMemory', type: '记忆召回工具',
    description: '从 Redis 读取用户跨会话长期记忆，用于追问和个性化回答场景',
    caps: ['长期记忆', 'Redis', '个性化'],
    color: '#fbbf24', bg: 'rgba(251,191,36,0.1)', border: 'rgba(251,191,36,0.2)', svgIcon: MemorySvg,
  },
  store_memory: {
    name: 'StoreMemory', type: '记忆写入工具',
    description: '将用户明确表达的偏好、称呼等长期事实持久化到 Redis，TTL 30 天',
    caps: ['长期记忆', 'Redis', '写入'],
    color: '#fb923c', bg: 'rgba(251,146,60,0.1)', border: 'rgba(251,146,60,0.2)', svgIcon: MemorySvg,
  },
}

const tools = ref<Tool[]>([])
const callLogs = ref<{ time: string; tool: string; latency: number; success: boolean; testResult: string }[]>([])

const enabledCount = computed(() => tools.value.filter(t => t.enabled).length)

const loadStats = async () => {
  loading.value = true
  try {
    const list = await mcpApi.listTools()
    tools.value = list.map(t => {
      const display = TOOL_DISPLAY[t.name] ?? {
        name: t.name, type: t.mode === 'remote' ? '远程工具' : '内置工具',
        description: t.description ?? '',
        caps: [], color: '#94a3b8',
        bg: 'rgba(148,163,184,0.1)', border: 'rgba(148,163,184,0.2)',
        svgIcon: DocSearchSvg,
      }
      return {
        dbId: t.id,
        toolName: t.name,
        ...display,
        enabled: t.status === 'active',
        testing: false,
        calls: t.callCount ?? 0,
        avgLatency: t.avgLatencyMs ?? 0,
      }
    })
  } catch {
    ElMessage.error('加载工具列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadStats)

const onToggle = async (tool: Tool, val: boolean) => {
  const newStatus = val ? 'active' : 'disabled'
  try {
    await mcpApi.updateStatus(tool.dbId, newStatus)
    tool.enabled = val
    ElMessage.success(`${tool.name} 已${val ? '启用' : '停用'}`)
  } catch {
    tool.enabled = !val
    ElMessage.error('状态更新失败')
  }
}

const testTool = (tool: Tool) => {
  currentTool.value = tool
  testResult.value = null
  testDialogVisible.value = true
}

const runTest = async () => {
  if (!currentTool.value) return
  testRunning.value = true
  testResult.value = null
  try {
    const res = await mcpApi.testTool(currentTool.value.dbId)
    const success = res.testResult === 'SUCCESS'
    testResult.value = {
      success,
      latency: res.latencyMs,
      output: JSON.stringify(res.sampleOutput ?? { message: res.message }, null, 2),
    }
    callLogs.value.unshift({
      time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      tool: currentTool.value.name,
      latency: res.latencyMs,
      success,
      testResult: res.testResult,
    })
    if (callLogs.value.length > 20) callLogs.value.pop()
    await loadStats()
  } catch (e: any) {
    testResult.value = { success: false, latency: 0, output: e?.message ?? '请求失败' }
  } finally {
    testRunning.value = false
  }
}

const clearLogs = () => { callLogs.value = [] }

const getLatencyClass = (ms: number) => {
  if (ms < 100) return 'lat-fast'
  if (ms < 500) return 'lat-ok'
  return 'lat-slow'
}
const getToolColor = (name: string) => {
  const map: Record<string, string> = { DocSearch: '#38bdf8', KeywordSearch: '#818cf8', WebSearch: '#34d399', RecallMemory: '#fbbf24', StoreMemory: '#fb923c' }
  return map[name] || '#94a3b8'
}
const formatNum = (n: number) => n >= 1000 ? `${(n/1000).toFixed(1)}k` : String(n)
</script>


<style scoped>
.mcp-page { display: flex; flex-direction: column; gap: 20px; overflow-y: auto; }

.mcp-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.page-h2 { font-size: 22px; font-weight: 700; color: var(--ink-1); margin-bottom: 4px; }
.page-desc { font-size: 13px; color: #64748b; }
.header-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

.online-dot {
  display: inline-block;
  width: 7px; height: 7px;
  border-radius: 50%;
  background: #34d399;
  margin-right: 6px;
  animation: pulse 2s infinite;
}
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

/* 工具网格 */
.tools-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.tool-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: var(--transition);
}
.tool-card.enabled { border-color: rgba(255,255,255,0.09); }
.tool-card.enabled:hover { box-shadow: 0 4px 24px rgba(0,0,0,0.35); transform: translateY(-1px); }
.tool-card.disabled { opacity: 0.55; }

.tool-header { display: flex; align-items: center; gap: 12px; }
.tool-icon {
  width: 44px; height: 44px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.tool-svg { display: block; }
.tool-meta { flex: 1; }
.tool-name { font-size: 15px; font-weight: 700; color: var(--ink-1); }
.tool-type { font-size: 11px; color: #64748b; margin-top: 2px; }

.tool-desc { font-size: 12.5px; color: #64748b; line-height: 1.6; }

.tool-caps { display: flex; flex-wrap: wrap; gap: 6px; }
.cap-tag {
  font-size: 11px; font-weight: 600;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.07);
  border-radius: 10px;
  padding: 2px 8px;
  color: #94a3b8;
}

.tool-metrics {
  display: flex;
  align-items: center;
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
}
.metric-item { flex: 1; text-align: center; }
.metric-sep { width: 1px; height: 28px; background: var(--border); }
.metric-val { font-size: 18px; font-weight: 800; font-family: 'JetBrains Mono', monospace; color: var(--ink-1); }
.metric-key { font-size: 10px; color: #475569; margin-top: 2px; text-transform: uppercase; letter-spacing: 0.5px; }
.lat-fast { color: #34d399; }
.lat-ok   { color: #fbbf24; }
.lat-slow { color: #f87171; }

.trend-bar {
  height: 32px;
  display: flex;
  align-items: flex-end;
  gap: 3px;
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
  padding: 6px 10px 4px;
}
.trend-col {
  flex: 1;
  border-radius: 2px;
  min-height: 4px;
  opacity: 0.7;
  transition: var(--transition);
}
.tool-card:hover .trend-col { opacity: 1; }

.tool-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 4px;
}
.tool-status-text { font-size: 11px; font-weight: 600; }
.tool-status-text.enabled { color: #34d399; }
.tool-status-text.disabled { color: #475569; }

/* 日志卡 */
.log-card :deep(.el-card__header) { padding: 12px 16px !important; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.log-list { display: flex; flex-direction: column; gap: 1px; }
.log-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  transition: background 0.15s;
}
.log-item:hover { background: var(--bg-hover); }
.log-time { color: #475569; font-family: monospace; flex-shrink: 0; min-width: 70px; }
.log-tool { font-weight: 700; min-width: 110px; flex-shrink: 0; }
.log-query { color: #94a3b8; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.log-latency { color: #64748b; font-family: monospace; min-width: 60px; text-align: right; flex-shrink: 0; }
.log-status { font-size: 13px; font-weight: 700; flex-shrink: 0; }
.log-status.ok  { color: #34d399; }
.log-status.err { color: #f87171; }
.log-empty { text-align: center; color: #475569; padding: 24px; font-size: 13px; }

/* 测试结果 */
.test-result {
  margin-top: 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}
.test-result-header {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}
.test-result-body {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  color: #94a3b8;
  padding: 14px;
  overflow-x: auto;
  margin: 0;
  line-height: 1.55;
}
</style>
