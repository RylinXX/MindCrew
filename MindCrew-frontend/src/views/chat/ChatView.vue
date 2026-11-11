<template>
  <div class="chat-layout">
    <!-- ===== 左侧会话列表 ===== -->
    <aside class="chat-sidebar">
      <div class="sidebar-top">
        <button class="new-chat-btn" @click="newConversation">
          <el-icon size="15"><Plus /></el-icon>
          <span>新建对话</span>
        </button>
      </div>

      <!-- 知识库选择器 -->
      <div class="kb-selector" v-if="knowledgeBases.length">
        <div class="kb-label">
          <el-icon size="12"><FolderOpened /></el-icon>
          <span>检索范围</span>
          <span class="kb-optional">可选</span>
        </div>
        <el-select
          v-model="selectedKbIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          filterable
          placeholder="默认全库智能检索，可按需缩小范围"
          clearable
          size="small"
          style="width: 100%"
        >
          <el-option
            v-for="kb in knowledgeBases"
            :key="kb.id"
            :label="kb.name"
            :value="kb.id"
          >
            <div style="display:flex;align-items:center;gap:6px">
              <span style="width:6px;height:6px;border-radius:50%;background:#34d399;flex-shrink:0"></span>
              {{ kb.name }}
            </div>
          </el-option>
        </el-select>
        <div class="kb-hint">
          <span v-if="selectedKbIds.length === 0">当前模式：直接提问，系统自动在全部知识库中检索相关文档</span>
          <span v-else>当前模式：仅在 {{ selectedKbIds.length }} 个选中文档中检索</span>
        </div>
      </div>

      <!-- 会话列表 -->
      <div class="conv-list" v-loading="convLoading">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: currentConvId === conv.id }"
          @click="switchConversation(conv.id)"
        >
          <el-icon size="13" class="conv-icon"><ChatDotRound /></el-icon>
          <div class="conv-info">
            <div class="conv-title">{{ conv.title || '未命名对话' }}</div>
            <div class="conv-meta">{{ formatTime(conv.lastActive) }}</div>
          </div>
          <div class="conv-btns">
            <button class="conv-btn" title="删除" @click.stop="deleteConversation(conv.id)">
              <el-icon size="12"><Delete /></el-icon>
            </button>
          </div>
        </div>

        <div v-if="conversations.length === 0 && !convLoading" class="empty-conv">
          <div class="empty-icon">
            <el-icon size="28" color="#334155"><ChatLineSquare /></el-icon>
          </div>
          <p>暂无对话记录</p>
          <p>开始您的第一次提问吧</p>
        </div>
      </div>
    </aside>

    <!-- ===== 主聊天区域 ===== -->
    <div class="chat-main">

      <!-- 欢迎屏 -->
      <transition name="fade">
        <div v-if="showWelcome" class="welcome-screen">
          <div class="welcome-logo">
            <MindCrewIcon :size="48" color="#fff" accent-color="#38bdf8" />
          </div>
          <h1 class="welcome-title">MindCrew <span class="gradient-text">智能问答</span></h1>
          <p class="welcome-desc">先提问，系统会自动在全部知识库中检索相关文档，再生成可追溯答案</p>
          <div class="quick-grid">
            <button
              v-for="q in quickQuestions"
              :key="q.text"
              class="quick-item"
              @click="sendMessage(q.text)"
            >
              <span class="quick-icon">{{ q.icon }}</span>
              <span>{{ q.text }}</span>
            </button>
          </div>
          <div class="welcome-tips">
            <span>💡</span>
            <span>支持文档内容查询、概念解释、摘要生成等多种任务</span>
          </div>
        </div>
      </transition>

      <!-- 消息区 -->
      <div v-show="!showWelcome" class="messages-area" ref="messagesAreaRef">
        <div class="messages-inner">
          <div
            v-for="msg in messages"
            :key="msg.id ?? msg.tempId"
            class="message-row"
            :class="msg.role"
          >
            <!-- 用户消息 -->
            <template v-if="msg.role === 'user'">
              <div class="msg-spacer"></div>
              <div class="user-bubble">
                <!-- 图片附件（用户上传的） -->
                <div v-if="getMsgImages(msg).length" class="user-img-row">
                  <a v-for="(img, i) in getMsgImages(msg)" :key="i"
                     :href="img.url || img.previewUrl" target="_blank"
                     class="user-img-thumb">
                    <img :src="img.url || img.previewUrl" alt="" />
                  </a>
                </div>
                <div v-if="msg.content && msg.content !== '[图片]'" class="bubble-text">{{ msg.content }}</div>
              </div>
              <el-avatar :size="30" :src="userStore.userInfo?.avatar" class="msg-avatar user-av">
                {{ (userStore.userInfo?.nickname || 'U').charAt(0).toUpperCase() }}
              </el-avatar>
            </template>

            <!-- AI 消息 -->
            <template v-else>
              <div class="ai-avatar-wrap">
                <div class="ai-av">
                  <MindCrewIcon :size="16" color="#fff" accent-color="#38bdf8" />
                </div>
              </div>
              <div class="ai-bubble">
                <!-- ✓ 基于人工校正 角标 -->
                <div v-if="msg.fromGoldenPair" class="golden-badge" :title="`匹配问题：${msg.matchedQuestion || ''} · 相似度 ${((msg.goldenScore || 0) * 100).toFixed(0)}%`">
                  <el-icon size="13"><CircleCheckFilled /></el-icon>
                  <span>已审核标准答案</span>
                  <span v-if="msg.goldenScore" class="golden-score">{{ (msg.goldenScore * 100).toFixed(0) }}%</span>
                </div>

                <!-- 推理链步骤（意图识别等） -->
                <div v-if="msg.agentSteps && msg.agentSteps.length" class="agent-steps">
                  <div
                    class="steps-header"
                    @click="msg.showSteps = !msg.showSteps"
                  >
                    <el-icon size="12"><MagicStick /></el-icon>
                    <span>推理过程 ({{ msg.agentSteps.length }} 步)</span>
                    <el-icon class="toggle-icon" :class="{ rotated: msg.showSteps }"><ArrowDown /></el-icon>
                  </div>
                  <transition name="fade">
                    <div v-if="msg.showSteps" class="steps-body">
                      <div v-for="(step, i) in msg.agentSteps" :key="i" class="step-item">
                        <span class="step-badge" :class="step.type">{{ step.type }}</span>
                        <span class="step-text">{{ step.text }}</span>
                      </div>
                    </div>
                  </transition>
                </div>

                <!-- Markdown 渲染内容 -->
                <div
                  v-if="msg.isStreaming"
                  class="bubble-content bubble-content-streaming"
                >{{ msg.content }}</div>
                <div
                  v-else
                  class="bubble-content md-body"
                  v-html="msg.renderedHtml || renderMd(msg.content)"
                ></div>

                <!-- 流式光标 -->
                <span v-if="msg.isStreaming" class="cursor-blink">|</span>

                <!-- 来源引用 -->
                <div v-if="msg.sources && msg.sources.length" class="sources-panel">
                  <button class="sources-toggle" @click="msg.showSources = !msg.showSources">
                    <el-icon size="12"><Document /></el-icon>
                    <span>参考来源 {{ msg.sources.length }} 条</span>
                    <el-icon class="toggle-icon" :class="{ rotated: msg.showSources }"><ArrowDown /></el-icon>
                  </button>
                  <transition name="fade">
                    <div v-if="msg.showSources" class="sources-list">
                      <div v-for="(src, i) in msg.sources" :key="i" class="source-card" :class="`media-${src.mediaType || 'document'}`">
                        <div class="src-header">
                          <span class="src-idx">[{{ src.index ?? (Number(i) + 1) }}]</span>
                          <span class="src-media-badge" v-if="src.mediaType">{{ mediaTypeLabel(src.mediaType) }}</span>
                          <span class="src-name">{{ src.name }}</span>
                          <span v-if="src.score" class="src-score">{{ (src.score * 100).toFixed(0) }}%</span>
                        </div>
                        <div class="src-excerpt">{{ src.content }}</div>
                        <div class="src-meta">
                          <span v-if="src.chapter">{{ src.chapter }}</span>
                          <span v-if="src.pageNumber"> · 第 {{ src.pageNumber }} 页</span>
                          <span v-if="src.startMs != null" class="src-timestamp">
                            · {{ formatMediaTime(src.startMs) }} ~ {{ formatMediaTime(src.endMs) }}
                          </span>
                          <span v-if="src.speakerId" class="src-speaker">· {{ src.speakerId }}</span>
                        </div>

                        <!-- 音频内嵌播放器 + 自动 seek -->
                        <div v-if="(src.mediaType === 'audio' || src.mediaType === 'video') && src.sourceObjectName" class="src-media-player">
                          <button class="src-play-btn" @click="openMediaSource(src)">
                            <el-icon :size="14"><VideoPlay /></el-icon>
                            <span>跳转到 {{ formatMediaTime(src.startMs) }} 播放</span>
                          </button>
                          <component
                            v-if="src._loaded"
                            :is="src.mediaType === 'audio' ? 'audio' : 'video'"
                            :ref="(el: any) => registerMediaPlayer(el, src)"
                            :src="src._mediaUrl"
                            controls
                            preload="metadata"
                            class="src-media-element"
                            @loadedmetadata="seekMediaTo(src)"
                          />
                        </div>
                      </div>
                    </div>
                  </transition>
                </div>

                <!-- 操作栏 -->
                <div v-if="!msg.isStreaming" class="msg-actions">
                  <button
                    class="action-btn thumb"
                    :class="{ active: msg.feedback === 1 }"
                    title="答案有用"
                    @click="submitFeedback(msg, 'up')"
                  >
                    <span class="thumb-emoji">👍</span>
                    <span class="thumb-label">有用</span>
                  </button>
                  <button
                    class="action-btn thumb"
                    :class="{ active: msg.feedback === -1, danger: msg.feedback === -1 }"
                    title="答案没用"
                    @click="submitFeedback(msg, 'down')"
                  >
                    <span class="thumb-emoji">👎</span>
                    <span class="thumb-label">没用</span>
                  </button>
                  <button class="action-btn" title="我来纠正答案" @click="openCorrectionDialog(msg)">
                    <el-icon size="13"><EditPen /></el-icon>
                  </button>
                  <button class="action-btn" title="查看检索过程" @click="showRetrievalLog(msg)">
                    <el-icon size="13"><DataAnalysis /></el-icon>
                  </button>
                  <button class="action-btn" title="复制" @click="copyContent(msg.content)">
                    <el-icon size="13"><CopyDocument /></el-icon>
                  </button>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- ===== 输入区域 ===== -->
      <div class="input-zone">
        <div class="input-card"
             :class="{ 'drag-over': isDraggingImage }"
             @dragover.prevent="isDraggingImage = true"
             @dragleave.prevent="isDraggingImage = false"
             @drop.prevent="handleImageDrop">

          <!-- 图片预览条 -->
          <div v-if="pendingImages.length" class="img-preview-row">
            <div v-for="(img, idx) in pendingImages" :key="img.localId" class="img-thumb"
                 :class="{ uploading: img.status === 'uploading', error: img.status === 'error' }">
              <img v-if="img.previewUrl" :src="img.previewUrl" alt="" />
              <div v-if="img.status === 'uploading'" class="thumb-overlay">
                <div class="spinner-mini"></div>
              </div>
              <div v-else-if="img.status === 'error'" class="thumb-overlay error-overlay" :title="img.error">
                <el-icon size="16"><WarningFilled /></el-icon>
              </div>
              <button class="thumb-close" @click="removePendingImage(idx)">
                <el-icon size="12"><Close /></el-icon>
              </button>
            </div>
          </div>

          <el-input
            v-model="inputText"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            :placeholder="isStreaming ? '正在生成，请稍候...' : (pendingImages.length ? '为图片添加描述（可选） · Enter 发送' : '输入您的问题，或粘贴/拖入图片（Enter 发送，Shift+Enter 换行）')"
            :disabled="isStreaming"
            @keydown.enter.exact.prevent="handleEnterSend"
            @paste="handlePaste"
            class="chat-input"
          />
          <div class="input-toolbar">
            <div class="toolbar-left">
              <input ref="imageFileInput" type="file" accept="image/*" multiple style="display:none"
                     @change="handleImageFileChange" />
              <button class="toolbar-icon-btn" :disabled="isStreaming || pendingImages.length >= 4"
                      :title="pendingImages.length >= 4 ? '最多 4 张图片' : '上传图片'"
                      @click="triggerImageUpload">
                <el-icon size="16"><Picture /></el-icon>
              </button>
              <span class="char-count" :class="{ warn: inputText.length > 800 }">
                {{ inputText.length }}
              </span>
            </div>
            <div class="toolbar-right">
              <button
                class="send-btn"
                :disabled="(!inputText.trim() && !pendingImages.length) || isStreaming || hasPendingUpload"
                :class="{ active: (inputText.trim() || pendingImages.length) && !isStreaming && !hasPendingUpload }"
                @click="handleSend"
              >
                <el-icon v-if="!isStreaming" size="16"><Position /></el-icon>
                <div v-else class="sending-dots">
                  <span></span><span></span><span></span>
                </div>
              </button>
            </div>
          </div>
        </div>
        <p class="input-disclaimer">
          默认自动全库检索；图片支持 JPG/PNG/WEBP，单张 ≤ 10MB，单次最多 4 张
        </p>
      </div>
    </div>

    <!-- ===== 检索过程弹窗 ===== -->
    <el-dialog
      v-model="retrievalLogVisible"
      title="RAG 检索过程"
      width="660px"
      class="rl-dialog"
    >
      <div v-if="currentRetrievalLog" class="rl-content">
        <!-- 管道步骤 -->
        <div class="rl-pipeline">
          <div v-for="(step, i) in pipelineSteps" :key="i" class="rl-step-wrap">
            <div class="rl-step" :style="{ '--step-color': step.color }">
              <span class="step-dot"></span>
              <span>{{ step.label }}</span>
            </div>
            <div v-if="i < pipelineSteps.length - 1" class="rl-step-arrow">→</div>
          </div>
        </div>

        <!-- Query 对比 -->
        <div class="rl-block">
          <div class="rl-block-title">Query 改写</div>
          <div class="rl-query-row">
            <div class="rl-query-box">
              <div class="rl-query-label">原始</div>
              <div class="rl-query-text">{{ currentRetrievalLog.originalQuery || '-' }}</div>
            </div>
            <el-icon color="#38bdf8"><Right /></el-icon>
            <div class="rl-query-box improved">
              <div class="rl-query-label">改写后</div>
              <div class="rl-query-text">{{ currentRetrievalLog.rewrittenQuery || '-' }}</div>
            </div>
          </div>
        </div>

        <!-- 检索数字 -->
        <div class="rl-block">
          <div class="rl-block-title">多路召回 & RRF 融合</div>
          <div class="rl-metrics">
            <div class="rl-metric">
              <div class="rl-metric-val" style="color:#818cf8">{{ currentRetrievalLog.vectorResults ?? 0 }}</div>
              <div class="rl-metric-lbl">向量检索</div>
            </div>
            <span style="color:#475569;font-size:20px">+</span>
            <div class="rl-metric">
              <div class="rl-metric-val" style="color:#38bdf8">{{ currentRetrievalLog.bm25Results ?? 0 }}</div>
              <div class="rl-metric-lbl">BM25 检索</div>
            </div>
            <span style="color:#475569;font-size:20px">→</span>
            <div class="rl-metric">
              <div class="rl-metric-val" style="color:#34d399">{{ currentRetrievalLog.rrfCount ?? 0 }}</div>
              <div class="rl-metric-lbl">RRF 融合</div>
            </div>
          </div>
        </div>

        <!-- 结果状态 -->
        <div class="rl-block">
          <div class="rl-block-title">重排序结果</div>
          <div class="rl-result-row">
            <span style="color:#94a3b8">
              注入 <strong style="color:var(--ink-1)">{{ currentRetrievalLog.rerankTop ?? 5 }}</strong> 条上下文
            </span>
            <el-tag :type="currentRetrievalLog.isFallback ? 'warning' : 'success'" size="large" effect="light">
              {{ currentRetrievalLog.isFallback ? '⚠ 低置信度，兜底响应' : '✓ 检索命中，正常响应' }}
            </el-tag>
          </div>
        </div>
      </div>
      <div v-else class="rl-empty">暂无检索日志</div>
    </el-dialog>

    <!-- ===== 纠正答案对话框 ===== -->
    <el-dialog
      v-model="correctionVisible"
      title="我来提供正确答案"
      width="640px"
      :close-on-click-modal="false"
      class="correction-dialog"
    >
      <div class="correction-hint">
        <el-icon size="14" color="#38bdf8"><EditPen /></el-icon>
        <span>你的修正会进入审核队列，审核通过后会自动作为该类问题的标准答案，实现"AI 越用越准"</span>
      </div>
      <el-input
        v-model="correctionText"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 18 }"
        placeholder="请输入正确答案，或在 AI 答复的基础上修改"
        maxlength="5000"
        show-word-limit
      />
      <template #footer>
        <el-button @click="correctionVisible = false">取消</el-button>
        <el-button type="primary" :loading="correctionSubmitting" @click="submitCorrection">
          提交审核
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, nextTick, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, ChatDotRound, ChatLineSquare, MagicStick, ArrowDown,
         Document, DataAnalysis, CopyDocument,
         Position, FolderOpened, Right, VideoPlay,
         EditPen, CircleCheckFilled, Picture, Close, WarningFilled } from '@element-plus/icons-vue'
import { marked } from 'marked'
import { chatApi, type Conversation, type Message } from '@/api/chat'
import { feedbackApi } from '@/api/feedback'
import { knowledgeApi } from '@/api/knowledge'
import { useUserStore } from '@/stores/user'
import MindCrewIcon from '@/components/MindCrewIcon.vue'

// 配置 marked
marked.setOptions({ breaks: true, gfm: true })

const route     = useRoute()
const userStore = useUserStore()

// ── 状态 ──
const conversations    = ref<Conversation[]>([])
const convLoading      = ref(false)
const currentConvId    = ref<number | null>(null)
const messages         = ref<any[]>([])
const messagesAreaRef  = ref<HTMLElement>()
const inputText        = ref('')

// ── 图片输入 · 任务 10 ──
interface PendingImage {
  localId: number
  file: File
  previewUrl: string         // 本地 blob URL，仅用于预览
  status: 'uploading' | 'done' | 'error'
  objectName?: string        // 上传成功后的 OSS objectName
  url?: string               // 后端返回的可访问 URL
  error?: string
}
const pendingImages = ref<PendingImage[]>([])
const isDraggingImage = ref(false)
const imageFileInput = ref<HTMLInputElement>()
const hasPendingUpload = computed(() => pendingImages.value.some(i => i.status === 'uploading'))

const triggerImageUpload = () => imageFileInput.value?.click()

const handleImageFileChange = (e: Event) => {
  const files = (e.target as HTMLInputElement).files
  if (files) addImages(Array.from(files))
  if (imageFileInput.value) imageFileInput.value.value = ''
}

const handleImageDrop = (e: DragEvent) => {
  isDraggingImage.value = false
  const files = e.dataTransfer?.files
  if (!files) return
  const imgs = Array.from(files).filter(f => f.type.startsWith('image/'))
  if (imgs.length) addImages(imgs)
}

const handlePaste = (e: ClipboardEvent) => {
  const items = e.clipboardData?.items
  if (!items) return
  const imgs: File[] = []
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const f = item.getAsFile()
      if (f) imgs.push(f)
    }
  }
  if (imgs.length) {
    e.preventDefault()
    addImages(imgs)
  }
}

const addImages = (files: File[]) => {
  const remaining = 4 - pendingImages.value.length
  if (remaining <= 0) {
    ElMessage.warning('单次最多 4 张图片')
    return
  }
  const accepted = files.slice(0, remaining)
  if (files.length > remaining) ElMessage.warning(`已选 ${pendingImages.value.length} 张，本次只接受前 ${remaining} 张`)

  for (const f of accepted) {
    if (f.size > 10 * 1024 * 1024) {
      ElMessage.error(`${f.name} 超过 10MB，已跳过`)
      continue
    }
    const pi: PendingImage = {
      localId: Date.now() + Math.random(),
      file: f,
      previewUrl: URL.createObjectURL(f),
      status: 'uploading',
    }
    pendingImages.value.push(pi)
    uploadImage(pi)
  }
}

const uploadImage = async (pi: PendingImage) => {
  const fd = new FormData()
  fd.append('file', pi.file)
  try {
    const res = await fetch('/api/v2/chat/upload-image', {
      method: 'POST',
      body: fd,
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') },
    })
    if (!res.ok) {
      const text = await res.text()
      throw new Error(`HTTP ${res.status} · ${text.slice(0, 200)}`)
    }
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '上传失败')
    pi.status = 'done'
    pi.objectName = json.data.objectName
    pi.url = json.data.url
  } catch (e: any) {
    pi.status = 'error'
    pi.error  = e?.message || '上传失败'
    ElMessage.error(`图片上传失败：${pi.error}`)
  }
}

const removePendingImage = (idx: number) => {
  const img = pendingImages.value[idx]
  if (img?.previewUrl) URL.revokeObjectURL(img.previewUrl)
  pendingImages.value.splice(idx, 1)
}

/** 从消息里抽出图片附件（兼容新消息的 .images 和历史消息从 sources 解析） */
const getMsgImages = (msg: any): { url?: string; previewUrl?: string }[] => {
  if (msg.images && msg.images.length) return msg.images
  if (!msg.sources) return []
  const arr = typeof msg.sources === 'string' ? safeJsonParse(msg.sources) : msg.sources
  if (!Array.isArray(arr)) return []
  return arr.filter((s: any) => s.type === 'user_image' && (s.url || s.objectName))
            .map((s: any) => ({ url: s.url }))
}

const safeJsonParse = (s: string): any => {
  try { return JSON.parse(s) } catch { return null }
}
const isStreaming      = ref(false)
const knowledgeBases   = ref<{ id: number; name: string }[]>([])
const selectedKbIds    = ref<number[]>([])
const retrievalLogVisible = ref(false)
const currentRetrievalLog = ref<any>(null)
let scrollScheduled = false

const showWelcome = computed(() => !currentConvId.value && messages.value.length === 0)

const quickQuestions = [
  { icon: '📄', text: '帮我总结这份文档的主要内容' },
  { icon: '🔍', text: '文档中关于权限管理的部分在哪里？' },
  { icon: '💡', text: '请解释文档中的核心概念' },
  { icon: '📊', text: '帮我提取文档中的关键数据指标' },
]

const pipelineSteps = [
  { label: 'Query 改写', color: '#38bdf8' },
  { label: '多路召回',   color: '#818cf8' },
  { label: 'RRF 融合',  color: '#34d399' },
  { label: 'Cross重排', color: '#fbbf24' },
  { label: 'LLM 生成',  color: '#f87171' },
]

// ── 生命周期 ──
onMounted(async () => {
  await Promise.all([loadConversations(), loadKnowledgeBases()])
  const idParam = route.params['id']
  if (idParam) switchConversation(Number(idParam))
})

// ── 知识库 ──
const loadKnowledgeBases = async () => {
  try {
    const res = await knowledgeApi.list({ size: 50, status: 'ready' })
    knowledgeBases.value = (res.records || []).map((kb: any) => ({ id: kb.id, name: kb.name }))
  } catch { /* ignore */ }
}

// ── 会话管理 ──
const loadConversations = async () => {
  convLoading.value = true
  try {
    const res = await chatApi.listConversations({ size: 50 })
    conversations.value = res.records || []
  } finally {
    convLoading.value = false
  }
}

const switchConversation = async (convId: number) => {
  currentConvId.value = convId

  // 从会话中恢复选中的知识库
  const conv = conversations.value.find(c => c.id === convId)
  selectedKbIds.value = parseKbIds(conv?.kbIds)

  const res = await chatApi.getHistory(convId, { size: 100 })
  messages.value = (res.records || []).map((m: Message) => ({
    ...m,
    sources: m.sources ? (typeof m.sources === 'string' ? JSON.parse(m.sources) : m.sources) : null,
    retrievalLog: m.retrievalLog ? (typeof m.retrievalLog === 'string' ? JSON.parse(m.retrievalLog) : m.retrievalLog) : null,
    renderedHtml: m.role === 'assistant' ? renderMd(m.content) : '',
    showSources: false,
    showSteps: false,
    isStreaming: false,
  }))
  await scrollToBottom()
}

const parseKbIds = (kbIdsStr?: string): number[] => {
  if (!kbIdsStr) return []
  try {
    return JSON.parse(kbIdsStr) as number[]
  } catch {
    return []
  }
}

const newConversation = () => {
  currentConvId.value = null
  messages.value = []
  inputText.value = ''
  selectedKbIds.value = []
}

const deleteConversation = async (convId: number) => {
  await ElMessageBox.confirm('确认删除该对话？', '提示', { type: 'warning' })
  await chatApi.deleteConversation(convId)
  if (currentConvId.value === convId) newConversation()
  await loadConversations()
  ElMessage.success('已删除')
}

// ── 发送 ──
const handleEnterSend = (e: KeyboardEvent) => { if (!e.shiftKey) handleSend() }
const handleSend = () => sendMessage(inputText.value)

const sendMessage = async (text: string) => {
  const content = text.trim()
  if ((!content && !pendingImages.value.length) || isStreaming.value) return
  if (hasPendingUpload.value) {
    ElMessage.warning('图片还在上传中，请稍候')
    return
  }

  // 收集已上传图片的 objectName（务实：必须是真实上传成功的，不带半成品）
  const uploadedImages = pendingImages.value.filter(i => i.status === 'done' && i.objectName)
  const imageObjectNames = uploadedImages.map(i => i.objectName!)
  const userImageSources = uploadedImages.map(i => ({
    type: 'user_image',
    objectName: i.objectName,
    url: i.url,
  }))
  // 提交后清空待发图片
  const sentImages = [...pendingImages.value]
  pendingImages.value = []

  inputText.value = ''
  isStreaming.value = true

  // 用户消息（图片直接挂在 sources）
  messages.value.push({
    tempId: Date.now(),
    role: 'user',
    content: content || (sentImages.length ? '[图片]' : ''),
    isStreaming: false,
    images: sentImages,
    sources: userImageSources.length ? userImageSources : null,
  } as any)

  const aiMsg = reactive({
    tempId: Date.now() + 1,
    role: 'assistant',
    content: '',
    renderedHtml: '',
    sources: null as any,
    showSources: false,
    agentSteps: [] as { type: string; text: string }[],
    showSteps: false,
    isStreaming: true,
    feedback: 0,
    retrievalLog: null as any,
    // Golden Pair 命中标记（golden-hit / done 事件回填）
    fromGoldenPair: false,
    goldenPairId: null as number | null,
    goldenScore: null as number | null,
    matchedQuestion: '' as string,
  })
  messages.value.push(aiMsg)
  await scrollToBottom()

  let pendingTokenBuffer = ''
  let flushTimer: number | null = null

  const flushPendingTokens = () => {
    if (!pendingTokenBuffer) return
    aiMsg.content += pendingTokenBuffer
    pendingTokenBuffer = ''
    scheduleScrollToBottom()
  }

  const scheduleTokenFlush = () => {
    if (flushTimer !== null) return
    flushTimer = window.setTimeout(() => {
      flushTimer = null
      flushPendingTokens()
    }, 40)
  }

  // SSE 连接
  const params = new URLSearchParams({ message: content })
  if (currentConvId.value) params.append('conversationId', String(currentConvId.value))
  if (selectedKbIds.value.length) {
    params.append('kbIds', selectedKbIds.value.join(','))
  }
  if (imageObjectNames.length) {
    params.append('imageObjectNames', imageObjectNames.join(','))
  }
  const token = localStorage.getItem('token') || ''
  params.append('token', token)

  const sse = new EventSource(`/api/v2/chat/stream?${params}`)

  sse.addEventListener('token', (e) => {
    const d = JSON.parse(e.data)
    pendingTokenBuffer += d.content ?? ''
    scheduleTokenFlush()
  })

  // 图片分析进度 · 任务 10
  sse.addEventListener('image-analysis', (e) => {
    const d = JSON.parse(e.data)
    if (d.status === 'start') {
      aiMsg.agentSteps.push({ type: 'image', text: `开始分析 ${d.imageCount} 张图片…` })
    } else if (d.status === 'done') {
      aiMsg.agentSteps.push({ type: 'image', text: `图片分析完成 · ${d.imageCount} 张 · 用时 ${d.elapsedMs}ms` })
    } else if (d.status === 'error') {
      aiMsg.agentSteps.push({ type: 'image', text: `图片 #${d.imageIndex} 识别失败：${d.message}` })
    }
  })

  // Golden Pair 命中事件 · 标记为"基于人工校正"
  sse.addEventListener('golden-hit', (e) => {
    const d = JSON.parse(e.data)
    aiMsg.fromGoldenPair = true
    aiMsg.goldenPairId   = d.pairId
    aiMsg.goldenScore    = d.score
    aiMsg.matchedQuestion = d.matchedQuestion
  })

  // Agent 推理链事件（v2 Agent 事件格式）
  const agentEvents = ['intent', 'rewrite', 'retrieval', 'rerank', 'reflection']
  agentEvents.forEach(evt => {
    sse.addEventListener(evt, (e) => {
      const d = JSON.parse(e.data)
      let text = ''
      if (evt === 'intent') text = `意图：${d.intentType}（置信度 ${(d.confidence * 100).toFixed(0)}%）`
      else if (evt === 'rewrite') text = d.fromCache ? `已改写（缓存）` : `改写：${d.rewritten}`
      else if (evt === 'retrieval') text = `召回 ${d.totalCount} 条切片`
      else if (evt === 'rerank') text = `重排序 Top-${d.topK}，压缩至 ${d.compressed} 条`
      else if (evt === 'reflection') text = `自纠错第${d.round}轮：${d.passed ? '通过' : '未通过'}（置信度 ${(d.confidence * 100).toFixed(0)}%）`
      else text = d.text || d.content || JSON.stringify(d)
      aiMsg.agentSteps.push({ type: evt, text })
    })
  })

  sse.addEventListener('done', (e) => {
    const d = JSON.parse(e.data)
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer)
      flushTimer = null
    }
    flushPendingTokens()
    aiMsg.sources      = d.sources || null
    aiMsg.retrievalLog = d.retrievalLog || null
    aiMsg.renderedHtml = renderMd(aiMsg.content)
    aiMsg.isStreaming  = false
    // done 里也可能带 fromGoldenPair（双保险）
    if (d.fromGoldenPair) {
      aiMsg.fromGoldenPair = true
      aiMsg.goldenPairId   = d.pairId
      aiMsg.goldenScore    = d.score
    }
    isStreaming.value  = false
    if (d.conversationId) currentConvId.value = d.conversationId
    sse.close()
    loadConversations()
    scheduleScrollToBottom()
  })

  sse.addEventListener('error', () => {
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer)
      flushTimer = null
    }
    flushPendingTokens()
    aiMsg.content    = aiMsg.content || '生成失败，请重试。'
    aiMsg.renderedHtml = renderMd(aiMsg.content)
    aiMsg.isStreaming = false
    isStreaming.value = false
    sse.close()
  })

  sse.onerror = () => {
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer)
      flushTimer = null
    }
    flushPendingTokens()
    aiMsg.renderedHtml = renderMd(aiMsg.content)
    aiMsg.isStreaming = false
    isStreaming.value = false
    sse.close()
  }
}

// ── 工具函数 ──
const renderMd = (content: string) => {
  if (!content) return ''
  return marked.parse(content) as string
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesAreaRef.value) {
    messagesAreaRef.value.scrollTop = messagesAreaRef.value.scrollHeight
  }
}

const scheduleScrollToBottom = () => {
  if (scrollScheduled) return
  scrollScheduled = true
  requestAnimationFrame(async () => {
    await scrollToBottom()
    scrollScheduled = false
  })
}

const copyContent = async (content: string) => {
  await navigator.clipboard.writeText(content)
  ElMessage.success('已复制')
}

const submitFeedback = async (msg: any, rating: 'up' | 'down') => {
  if (!msg.id) return
  try {
    await feedbackApi.submit({ messageId: msg.id, rating })
    msg.feedback = rating === 'up' ? 1 : -1
    ElMessage.success(rating === 'up' ? '感谢反馈！' : '已记录，可点 ✎ 提供正确答案帮我们改进')
  } catch (e: any) {
    ElMessage.error('提交失败：' + (e?.message || ''))
  }
}

// 纠正对话框 · 用户可输入正确答案，提交后进入审核队列
const correctionVisible = ref(false)
const correctionTarget  = ref<any>(null)
const correctionText    = ref('')
const correctionSubmitting = ref(false)

const openCorrectionDialog = (msg: any) => {
  if (!msg.id) {
    ElMessage.warning('消息尚未保存，无法纠正')
    return
  }
  correctionTarget.value = msg
  correctionText.value = msg.content || ''
  correctionVisible.value = true
}

const submitCorrection = async () => {
  if (!correctionTarget.value || !correctionText.value.trim()) {
    ElMessage.warning('请输入正确答案')
    return
  }
  correctionSubmitting.value = true
  try {
    await feedbackApi.submit({
      messageId: correctionTarget.value.id,
      rating: 'down',
      correctionText: correctionText.value.trim(),
    })
    correctionTarget.value.feedback = -1
    correctionTarget.value.hasCorrection = true
    ElMessage.success('已提交，等待审核员收录后将自动改进')
    correctionVisible.value = false
    correctionTarget.value = null
    correctionText.value = ''
  } catch (e: any) {
    ElMessage.error('提交失败：' + (e?.message || ''))
  } finally {
    correctionSubmitting.value = false
  }
}

const showRetrievalLog = (msg: any) => {
  currentRetrievalLog.value = msg.retrievalLog
  retrievalLogVisible.value = true
}

const formatTime = (t: string) => {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const diff = (now.getTime() - d.getTime()) / 1000
  if (diff < 60)   return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

// ─────────────────────────────────────────────
// 时间戳级溯源辅助函数
// ─────────────────────────────────────────────
const mediaTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    audio: '音频',
    video: '视频',
    image: '图片',
    pdf: 'PDF',
    pptx: 'PPT',
    xlsx: 'Excel',
    document: '文档',
    text: '文本'
  }
  return map[type] || type
}

const formatMediaTime = (ms?: number | null) => {
  if (ms == null) return ''
  const sec = Math.floor(ms / 1000)
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 缓存 objectName → 预签名 URL，避免重复请求 */
const mediaUrlCache = new Map<string, string>()
const mediaPlayerRefs = new Map<string, HTMLMediaElement>()

async function openMediaSource(src: any) {
  if (!src.sourceObjectName) return
  // 已加载就直接 seek
  if (src._loaded) {
    seekMediaTo(src)
    return
  }
  // 拉预签名 URL
  let url = mediaUrlCache.get(src.sourceObjectName)
  if (!url) {
    try {
      const res: any = await chatApi.fetchMediaUrl(src.sourceObjectName)
      url = (res?.data?.url) || res?.url
      if (url) mediaUrlCache.set(src.sourceObjectName, url)
    } catch (e) {
      ElMessage.error('获取媒体文件 URL 失败')
      return
    }
  }
  if (!url) return
  src._mediaUrl = url
  src._loaded = true
}

function registerMediaPlayer(el: any, src: any) {
  if (el && src.sourceObjectName) {
    mediaPlayerRefs.set(src.sourceObjectName + '#' + src.startMs, el)
  }
}

function seekMediaTo(src: any) {
  if (src.startMs == null) return
  const key = src.sourceObjectName + '#' + src.startMs
  const el = mediaPlayerRefs.get(key)
  if (el) {
    try {
      el.currentTime = src.startMs / 1000
      el.play().catch(() => { /* 浏览器可能拦截自动播放，用户点 play 即可 */ })
    } catch (e) {
      console.warn('seek 失败', e)
    }
  }
}
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
  background: var(--bg-base);
}

/* ─── 左侧会话列表 ─── */
.chat-sidebar {
  width: 230px;
  flex-shrink: 0;
  background: var(--bg-surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-top { padding: 12px; flex-shrink: 0; }
.new-chat-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px 0;
  background: rgba(56,189,248,0.08);
  border: 1px dashed rgba(56,189,248,0.3);
  border-radius: var(--radius-sm);
  color: var(--primary);
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition);
}
.new-chat-btn:hover { background: rgba(56,189,248,0.14); border-style: solid; }

.kb-selector {
  padding: 0 12px 10px;
  flex-shrink: 0;
}
.kb-label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: #475569;
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  margin-bottom: 6px;
}
.kb-optional {
  margin-left: auto;
  font-size: 10px;
  color: #64748b;
  letter-spacing: 0;
  text-transform: none;
}
.kb-hint {
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: #64748b;
}

.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 6px;
}

.conv-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: var(--transition);
  position: relative;
}
.conv-item:hover { background: var(--bg-hover); }
.conv-item:hover .conv-btns { opacity: 1; }
.conv-item.active { background: rgba(56,189,248,0.08); }
.conv-item.active .conv-icon { color: var(--primary); }

.conv-icon { color: #334155; margin-top: 2px; flex-shrink: 0; }

.conv-info { flex: 1; min-width: 0; }
.conv-title {
  font-size: 12.5px;
  font-weight: 500;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-item.active .conv-title { color: var(--ink-1); }
.conv-meta { font-size: 11px; color: #334155; margin-top: 2px; }

.conv-btns { opacity: 0; display: flex; gap: 2px; flex-shrink: 0; }
.conv-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: #475569;
  padding: 3px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  transition: var(--transition);
}
.conv-btn:hover { color: #f87171; background: rgba(248,113,113,0.1); }

.empty-conv {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 40px 16px;
  text-align: center;
}
.empty-icon { margin-bottom: 8px; }
.empty-conv p { font-size: 12px; color: #334155; line-height: 1.6; }

/* ─── 主聊天区 ─── */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

/* 欢迎屏 */
.welcome-screen {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  gap: 20px;
  background: var(--bg-base);
}
.welcome-logo {
  width: 72px; height: 72px;
  background: linear-gradient(135deg, #1e3a5f, #0e2640);
  border: 1px solid rgba(56,189,248,0.25);
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 30px rgba(56,189,248,0.15);
}
.welcome-title { font-size: 26px; font-weight: 700; color: var(--ink-1); }
.welcome-desc { font-size: 14px; color: #64748b; text-align: center; max-width: 380px; }
.quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  max-width: 560px;
  width: 100%;
}
.quick-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  transition: var(--transition);
  font-size: 13px;
  color: #94a3b8;
  text-align: left;
}
.quick-item:hover { border-color: var(--primary); background: rgba(56,189,248,0.06); color: var(--ink-1); }
.quick-icon { font-size: 16px; flex-shrink: 0; }
.welcome-tips {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #334155;
  background: rgba(255,255,255,0.02);
  border: 1px solid var(--border);
  border-radius: 20px;
  padding: 6px 14px;
}

/* 消息区 */
.messages-area {
  flex: 1;
  overflow-y: auto;
  background: var(--bg-base);
}
.messages-inner {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

/* 用户消息 */
.message-row.user { flex-direction: row; }
.msg-spacer { flex: 1; }
.msg-avatar { flex-shrink: 0; margin-top: 2px; }
.user-av { background: linear-gradient(135deg, #1e40af, #4f46e5) !important; font-size: 12px; }

.user-bubble {
  max-width: 70%;
}
.user-bubble .bubble-text {
  background: linear-gradient(135deg, #1e40af, #2563eb);
  color: #e2e8f0;
  border-radius: 14px 14px 4px 14px;
  padding: 11px 15px;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

/* AI 消息 */
.ai-avatar-wrap { flex-shrink: 0; margin-top: 4px; }
.ai-av {
  width: 30px; height: 30px;
  background: linear-gradient(135deg, #1e3a5f, #0e2640);
  border: 1px solid rgba(56,189,248,0.2);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-bubble {
  flex: 1;
  max-width: calc(100% - 44px);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: 4px 14px 14px 14px;
  padding: 14px 16px;
  position: relative;
}
.bubble-content {
  line-height: 1.75;
  color: var(--ink-1);
  word-break: break-word;
}
.bubble-content-streaming {
  white-space: pre-wrap;
}

/* 推理链 */
.agent-steps {
  margin-bottom: 10px;
  background: rgba(56,189,248,0.04);
  border: 1px solid rgba(56,189,248,0.1);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.steps-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  cursor: pointer;
  font-size: 12px;
  color: #64748b;
  transition: var(--transition);
}
.steps-header:hover { color: var(--primary); }
.steps-header span { flex: 1; }
.toggle-icon { transition: transform 0.2s; color: #475569; }
.toggle-icon.rotated { transform: rotate(180deg); }
.steps-body { padding: 6px 10px 10px; display: flex; flex-direction: column; gap: 5px; }
.step-item { display: flex; align-items: flex-start; gap: 8px; }
.step-badge {
  font-size: 10px; font-weight: 700; letter-spacing: 0.5px;
  padding: 2px 6px; border-radius: 4px; flex-shrink: 0; text-transform: uppercase;
}
.step-badge.intent    { background: rgba(56,189,248,0.15); color: #38bdf8; }
.step-badge.rewrite   { background: rgba(129,140,248,0.15); color: #818cf8; }
.step-badge.retrieval { background: rgba(52,211,153,0.15); color: #34d399; }
.step-badge.rerank    { background: rgba(251,191,36,0.15); color: #fbbf24; }
.step-text { font-size: 12px; color: #64748b; line-height: 1.5; }

/* 流式光标 */
.cursor-blink {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--primary);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 0.8s step-end infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

/* 来源 */
.sources-panel { margin-top: 12px; }
.sources-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 12px;
  color: #64748b;
  padding: 5px 8px;
  border-radius: 5px;
  transition: var(--transition);
}
.sources-toggle:hover { background: var(--bg-hover); color: var(--primary); }
.sources-list { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.source-card {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}
.src-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.src-idx {
  font-size: 11px; font-weight: 700; font-family: monospace;
  color: var(--primary); background: var(--primary-dim);
  border-radius: 4px; padding: 1px 5px; flex-shrink: 0;
}
.src-name { font-size: 12px; font-weight: 600; color: #94a3b8; flex: 1; }
.src-score { font-size: 11px; color: #34d399; font-weight: 600; }
.src-excerpt { font-size: 12px; color: #64748b; line-height: 1.55; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.src-meta {
  font-size: 11px; color: #475569; margin-top: 4px;
  display: flex; flex-wrap: wrap; align-items: center; gap: 2px 4px;
}
.src-media-badge {
  font-size: 10px; font-weight: 600; padding: 1px 6px;
  border-radius: 999px; color: #fff;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  flex-shrink: 0;
}
.source-card.media-audio .src-media-badge { background: linear-gradient(135deg, #f59e0b, #d97706); }
.source-card.media-video .src-media-badge { background: linear-gradient(135deg, #ef4444, #b91c1c); }
.source-card.media-image .src-media-badge { background: linear-gradient(135deg, #10b981, #059669); }

.src-timestamp {
  font-family: 'JetBrains Mono', monospace;
  color: var(--primary);
  font-weight: 600;
}
.src-speaker { color: #94a3b8; }

/* 媒体播放器（音视频） */
.src-media-player {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.src-play-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.18), rgba(99, 102, 241, 0.12));
  border: 1px solid rgba(56, 189, 248, 0.32);
  color: #38bdf8;
  font-size: 12px;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.2s;
}
.src-play-btn:hover {
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.28), rgba(99, 102, 241, 0.2));
  transform: translateY(-1px);
}
.src-media-element {
  display: block;
  width: 100%;
  margin-top: 8px;
  border-radius: 8px;
  background: #000;
}
audio.src-media-element { height: 40px; background: transparent; }
video.src-media-element { max-height: 320px; }

/* 操作栏 */
/* ── 图片输入 · 任务 10 ── */
.input-card { position: relative; transition: border-color 0.15s; }
.input-card.drag-over {
  border-color: var(--primary, #38bdf8);
  background: rgba(56, 189, 248, 0.04);
}
.img-preview-row {
  display: flex;
  gap: 8px;
  padding: 8px 10px 4px;
  flex-wrap: wrap;
}
.img-thumb {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border, #334155);
  background: var(--bg-elevated, #1e293b);
}
.img-thumb img { width: 100%; height: 100%; object-fit: cover; }
.img-thumb.uploading { opacity: 0.7; }
.img-thumb.error { border-color: #ef4444; }
.thumb-overlay {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: rgba(15, 23, 42, 0.55);
  color: #fff;
}
.thumb-overlay.error-overlay { background: rgba(239, 68, 68, 0.7); }
.spinner-mini {
  width: 18px; height: 18px;
  border: 2px solid rgba(255,255,255,0.35);
  border-top-color: #38bdf8;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.thumb-close {
  position: absolute;
  top: 2px; right: 2px;
  width: 18px; height: 18px;
  border-radius: 50%;
  background: rgba(15, 23, 42, 0.7);
  border: none;
  color: #fff;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
}
.thumb-close:hover { background: #ef4444; }
.toolbar-icon-btn {
  width: 30px; height: 30px;
  border-radius: 8px;
  background: none;
  border: none;
  color: var(--ink-3, #94a3b8);
  cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: var(--transition);
}
.toolbar-icon-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--primary, #38bdf8);
}
.toolbar-icon-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.user-img-row {
  display: flex; gap: 6px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.user-img-thumb {
  display: block;
  width: 100px; height: 100px;
  border-radius: 8px;
  overflow: hidden;
  cursor: zoom-in;
}
.user-img-thumb img { width: 100%; height: 100%; object-fit: cover; }

.golden-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  margin-bottom: 10px;
  background: linear-gradient(135deg, rgba(52, 211, 153, 0.18), rgba(34, 197, 94, 0.10));
  border: 1px solid rgba(52, 211, 153, 0.35);
  border-radius: 999px;
  color: #34d399;
  font-size: 11.5px;
  font-weight: 600;
  cursor: help;
}
.golden-badge .el-icon { color: #34d399; }
.golden-score {
  margin-left: 4px;
  font-family: 'JetBrains Mono', monospace;
  color: #6ee7b7;
  font-size: 10.5px;
}
.correction-dialog .correction-hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 14px;
  background: rgba(56, 189, 248, 0.08);
  border: 1px solid rgba(56, 189, 248, 0.2);
  border-radius: 8px;
  font-size: 12.5px;
  color: #94a3b8;
  line-height: 1.55;
}
.msg-actions { display: flex; gap: 4px; margin-top: 10px; }
.action-btn {
  display: flex;
  align-items: center;
  background: none;
  border: none;
  cursor: pointer;
  color: #475569;
  padding: 4px 7px;
  border-radius: 5px;
  transition: var(--transition);
  font-size: 12px;
}
.action-btn:hover { background: var(--bg-hover); color: #94a3b8; }
.action-btn.active { color: var(--primary); }
.action-btn.danger { color: #f87171; }

/* 点赞 / 点踩按钮 · 强调可识别性 */
.action-btn.thumb {
  gap: 4px;
  padding: 4px 9px;
  border: 1px solid transparent;
}
.action-btn.thumb .thumb-emoji {
  font-size: 14px;
  line-height: 1;
  filter: grayscale(100%) opacity(0.6);
  transition: filter 0.15s;
}
.action-btn.thumb .thumb-label {
  font-size: 11.5px;
  font-weight: 500;
}
.action-btn.thumb:hover {
  background: rgba(56, 189, 248, 0.08);
  border-color: rgba(56, 189, 248, 0.25);
}
.action-btn.thumb:hover .thumb-emoji {
  filter: none;
}
.action-btn.thumb.active {
  background: rgba(56, 189, 248, 0.12);
  border-color: rgba(56, 189, 248, 0.4);
  color: #0284c7;
}
.action-btn.thumb.active .thumb-emoji {
  filter: none;
}
.action-btn.thumb.danger {
  background: rgba(239, 68, 68, 0.10);
  border-color: rgba(239, 68, 68, 0.35);
  color: #dc2626;
}
.action-btn.thumb.danger .thumb-emoji {
  filter: none;
}

/* 输入区 */
.input-zone {
  padding: 12px 20px 16px;
  max-width: 800px;
  width: 100%;
  margin: 0 auto;
  align-self: center;
  flex-shrink: 0;
}
.input-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 12px 14px 10px;
  transition: border-color 0.2s;
}
.input-card:focus-within { border-color: rgba(56,189,248,0.4); }

:deep(.chat-input .el-textarea__inner) {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  padding: 0 !important;
  min-height: 24px !important;
  color: var(--ink-1) !important;
  font-size: 14px;
  line-height: 1.65;
}

.input-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.char-count { font-size: 11px; color: #334155; font-family: monospace; }
.char-count.warn { color: #f87171; }

.send-btn {
  width: 34px; height: 34px;
  border-radius: 9px;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: var(--bg-elevated);
  color: #475569;
  transition: var(--transition);
}
.send-btn.active { background: var(--primary); color: #0d1117; }
.send-btn:disabled { cursor: not-allowed; opacity: 0.4; }
.send-btn.active:hover { background: #7dd3fc; }

.sending-dots { display: flex; gap: 3px; align-items: center; }
.sending-dots span {
  width: 4px; height: 4px;
  border-radius: 50%;
  background: #0d1117;
  animation: dots 1.2s infinite;
}
.sending-dots span:nth-child(2) { animation-delay: 0.2s; }
.sending-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes dots { 0%, 100% { opacity: 0.3; } 50% { opacity: 1; } }

.input-disclaimer { font-size: 11px; color: #334155; text-align: center; margin-top: 8px; }

/* ─── 检索日志弹窗 ─── */
:deep(.rl-dialog .el-dialog__body) { padding: 0 22px 22px; }

.rl-pipeline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 20px;
  padding: 12px 14px;
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
}
.rl-step-wrap { display: flex; align-items: center; gap: 4px; }
.rl-step {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 600;
  color: var(--step-color);
  background: color-mix(in srgb, var(--step-color) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--step-color) 25%, transparent);
  border-radius: 20px;
  padding: 4px 10px;
}
.step-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; flex-shrink: 0; }
.rl-step-arrow { color: #334155; font-size: 12px; }

.rl-block { margin-bottom: 18px; }
.rl-block-title { font-size: 12px; font-weight: 700; color: #64748b; letter-spacing: 0.5px; text-transform: uppercase; margin-bottom: 10px; }
.rl-query-row { display: flex; align-items: center; gap: 10px; }
.rl-query-box { flex: 1; background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px 12px; }
.rl-query-box.improved { border-color: rgba(56,189,248,0.2); }
.rl-query-label { font-size: 10px; font-weight: 700; color: #475569; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 4px; }
.rl-query-text { font-size: 13px; color: var(--ink-1); line-height: 1.5; }
.rl-metrics { display: flex; align-items: center; gap: 14px; }
.rl-metric { text-align: center; }
.rl-metric-val { font-size: 28px; font-weight: 800; font-family: 'JetBrains Mono', monospace; line-height: 1; }
.rl-metric-lbl { font-size: 11px; color: #64748b; margin-top: 4px; }
.rl-result-row { display: flex; align-items: center; justify-content: space-between; }
.rl-empty { text-align: center; color: #475569; padding: 32px; font-size: 14px; }
</style>
