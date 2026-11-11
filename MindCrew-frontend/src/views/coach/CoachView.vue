<template>
  <div class="coach-page">
    <!-- 顶部：标题 + 入口 -->
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">教练模式</h2>
          <span class="title-tag">AI 主动出题 · 检验学习效果</span>
        </div>
        <p class="page-desc">
          知识库会基于你导入的资料给你出考核题。答完会给评分和讲解，错题会推荐复习章节。
          越练越精准，AI 也会越来越懂你薄弱在哪里。
        </p>
      </div>
      <div class="header-actions">
        <el-button :icon="DataAnalysis" @click="showStats = true">我的学习</el-button>
        <el-button :icon="List" @click="loadHistory">历史会话</el-button>
      </div>
    </header>

    <!-- 三种主视图切换 -->
    <div v-if="view === 'idle'" class="start-card">
      <div class="start-icon">
        <el-icon size="42" color="#7c3aed"><MagicStick /></el-icon>
      </div>
      <h3>开始一次新练习</h3>
      <p class="hint">从下面挑选范围 + 难度，AI 将基于你的知识库出题</p>

      <div class="form-row">
        <label>知识库范围（不选 = 全量可访问）</label>
        <el-select
          v-model="startForm.kbIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="全部知识库"
          style="width: 100%"
          :loading="kbLoading"
        >
          <el-option
            v-for="kb in kbList"
            :key="kb.id"
            :label="kb.name"
            :value="kb.id"
          />
        </el-select>
      </div>

      <div class="form-row">
        <label>难度</label>
        <el-radio-group v-model="startForm.difficulty">
          <el-radio-button value="easy">简单</el-radio-button>
          <el-radio-button value="medium">中等</el-radio-button>
          <el-radio-button value="hard">困难</el-radio-button>
        </el-radio-group>
      </div>

      <div class="form-row">
        <label>题数</label>
        <el-input-number v-model="startForm.questionTotal" :min="3" :max="30" :step="1" />
      </div>

      <el-button
        type="primary"
        size="large"
        :loading="starting"
        @click="onStart"
        style="width: 100%; margin-top: 12px"
      >
        {{ starting ? `正在准备 ${startForm.questionTotal} 道题（约 ${expectedSeconds} 秒）...` : '启动练习' }}
      </el-button>
      <p v-if="starting" class="prepare-hint">
        AI 正在严格按知识库原文出题，并做反幻觉校验。准备好后做题秒级响应。
      </p>
    </div>

    <!-- 练习中 -->
    <div v-if="view === 'practice'" class="practice-area">
      <!-- session 顶部 banner -->
      <div class="session-banner">
        <div>
          <div class="banner-title">
            <el-tag size="small" type="warning">{{ difficultyLabel(session?.difficulty) }}</el-tag>
            <span>第 {{ (session?.questionDone || 0) + 1 }} / {{ session?.questionTotal }} 题</span>
            <span class="banner-scope" v-if="session?.kbScopeLabel">· {{ session.kbScopeLabel }}</span>
          </div>
          <el-progress
            :percentage="((session?.questionDone || 0) / (session?.questionTotal || 1)) * 100"
            :stroke-width="6"
            :show-text="false"
            color="#7c3aed"
          />
        </div>
        <el-button :icon="CircleClose" text @click="onAbort">退出</el-button>
      </div>

      <!-- 题目卡 -->
      <article class="q-card" v-loading="loadingQuestion" element-loading-text="出题中...">
        <header class="q-head" v-if="currentQuestion">
          <span class="seq">第 {{ currentQuestion.seq }} 题</span>
          <span class="q-type">{{ qTypeLabel(currentQuestion.questionType) }}</span>
          <span class="q-source" v-if="currentQuestion.sourceKbName">来源：{{ currentQuestion.sourceKbName }}</span>
        </header>

        <div v-if="currentQuestion" class="q-body">
          <div class="q-stem">{{ currentQuestion.question }}</div>

          <!-- 选择题 -->
          <div v-if="currentQuestion.questionType === 'multiple_choice' && questionOptions.length" class="q-options">
            <el-radio-group v-model="userAnswer" :disabled="!!lastAnswer">
              <el-radio
                v-for="opt in questionOptions"
                :key="opt"
                :label="extractChoiceLetter(opt)"
                :value="extractChoiceLetter(opt)"
                class="opt"
              >{{ opt }}</el-radio>
            </el-radio-group>
          </div>

          <!-- 判断题 -->
          <div v-else-if="currentQuestion.questionType === 'true_false'" class="q-options tf">
            <el-radio-group v-model="userAnswer" :disabled="!!lastAnswer">
              <el-radio-button value="对">对</el-radio-button>
              <el-radio-button value="错">错</el-radio-button>
            </el-radio-group>
          </div>

          <!-- 短答题 -->
          <div v-else class="q-options">
            <el-input
              v-model="userAnswer"
              type="textarea"
              :rows="4"
              :disabled="!!lastAnswer"
              placeholder="请输入你的答案..."
              maxlength="500"
              show-word-limit
            />
          </div>

          <!-- 评分反馈 -->
          <div v-if="lastAnswer" class="feedback" :class="judgeClass(lastAnswer.judgment)">
            <div class="fb-head">
              <span class="fb-score">{{ lastAnswer.score }} 分</span>
              <span class="fb-judge">{{ judgeLabel(lastAnswer.judgment) }}</span>
            </div>
            <div class="fb-text">{{ lastAnswer.feedback }}</div>
            <div class="fb-expected" v-if="currentQuestion.expectedAnswer">
              <span class="lbl">标准答案</span>
              {{ currentQuestion.expectedAnswer }}
            </div>
            <div class="fb-expected" v-if="currentQuestion.sourceQuote">
              <span class="lbl">原文出处</span>
              <span style="font-style: italic;">「{{ currentQuestion.sourceQuote }}」</span>
            </div>
            <div class="fb-explain" v-if="currentQuestion.explanation">
              <span class="lbl">解析</span>
              {{ currentQuestion.explanation }}
            </div>
          </div>

          <div class="q-actions">
            <el-button
              v-if="!lastAnswer"
              type="primary"
              :loading="submitting"
              :disabled="!userAnswer || (typeof userAnswer === 'string' && !userAnswer.trim())"
              @click="onSubmit"
            >提交答案</el-button>
            <el-button
              v-else
              type="primary"
              :loading="loadingQuestion"
              @click="loadNext"
            >
              {{ isLastQuestion ? '查看总结' : '下一题 →' }}
            </el-button>
          </div>
        </div>
      </article>
    </div>

    <!-- 总结报告 -->
    <div v-if="view === 'finished'" class="summary-card">
      <div class="summary-medal">
        <el-icon size="56" :color="medalColor"><Trophy /></el-icon>
      </div>
      <h3>本次练习完成</h3>
      <div class="summary-stats">
        <div class="stat">
          <span class="num">{{ session?.questionDone }}</span>
          <span class="lbl">已答题</span>
        </div>
        <div class="stat">
          <span class="num">{{ session?.correctCount }}</span>
          <span class="lbl">正确</span>
        </div>
        <div class="stat">
          <span class="num">{{ avgScore }}</span>
          <span class="lbl">平均分</span>
        </div>
      </div>
      <p class="summary-msg">{{ summaryMessage }}</p>
      <div class="summary-actions">
        <el-button @click="resetToIdle">再练一次</el-button>
        <el-button type="primary" @click="showDetail = true">查看详情</el-button>
      </div>
    </div>

    <!-- 历史会话抽屉 -->
    <el-drawer v-model="historyVisible" title="我的练习历史" size="560px">
      <div class="history-list" v-loading="historyLoading">
        <article
          v-for="s in historyList"
          :key="s.id"
          class="history-item"
          @click="openHistoryDetail(s)"
        >
          <div class="row1">
            <el-tag size="small" :type="statusTagType(s.status)">{{ statusLabel(s.status) }}</el-tag>
            <span class="diff">{{ difficultyLabel(s.difficulty) }}</span>
            <span class="time">{{ formatTime(s.startAt) }}</span>
          </div>
          <div class="row2">
            <span class="scope">{{ s.kbScopeLabel || '全量知识库' }}</span>
          </div>
          <div class="row3">
            <span>{{ s.questionDone }}/{{ s.questionTotal }} 题</span>
            <span class="dot">·</span>
            <span>对 {{ s.correctCount }}</span>
            <span class="dot">·</span>
            <span>平均 {{ s.questionDone ? Math.round(s.totalScore / s.questionDone) : 0 }} 分</span>
          </div>
        </article>
        <div v-if="!historyLoading && historyList.length === 0" class="empty">
          <p>还没有练习记录</p>
        </div>
      </div>
    </el-drawer>

    <!-- 我的统计抽屉 -->
    <el-drawer v-model="showStats" title="我的学习" size="520px">
      <div v-loading="statsLoading" class="stats-panel">
        <div class="stat-grid">
          <div class="stat-card">
            <div class="card-label">练习次数</div>
            <div class="card-num">{{ stats?.sessionCount ?? 0 }}</div>
            <div class="card-sub">完成 {{ stats?.finishedSessionCount ?? 0 }}</div>
          </div>
          <div class="stat-card">
            <div class="card-label">作答题数</div>
            <div class="card-num">{{ stats?.answeredQuestionCount ?? 0 }}</div>
            <div class="card-sub">对 {{ stats?.correctCount ?? 0 }}</div>
          </div>
          <div class="stat-card">
            <div class="card-label">平均分</div>
            <div class="card-num">{{ stats?.avgScore ?? 0 }}</div>
            <div class="card-sub">满分 100</div>
          </div>
          <div class="stat-card">
            <div class="card-label">正确率</div>
            <div class="card-num">{{ accuracyPct }}</div>
            <div class="card-sub">≥80 分判为正确</div>
          </div>
        </div>

        <h4 class="stats-sub">薄弱知识库 Top 5</h4>
        <div v-if="!stats?.weakKbs || stats.weakKbs.length === 0" class="empty">
          <p>暂无足够数据</p>
        </div>
        <div v-else class="weak-list">
          <div v-for="w in stats.weakKbs" :key="w.kbId" class="weak-item">
            <div class="weak-name">{{ w.kbName }}</div>
            <div class="weak-bar">
              <div class="weak-fill" :style="{ width: w.avgScore + '%' }"></div>
            </div>
            <div class="weak-score">{{ w.avgScore }} 分 · {{ w.answered }} 题</div>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 详情对话框（完成后展示题目+答案） -->
    <el-dialog v-model="showDetail" title="练习详情" width="780px" top="5vh">
      <div v-loading="detailLoading" class="detail-list">
        <article v-for="q in detailQuestions" :key="q.id" class="detail-item">
          <div class="d-head">
            <span class="d-seq">Q{{ q.seq }}</span>
            <span class="d-type">{{ qTypeLabel(q.questionType) }}</span>
            <span class="d-source">{{ q.sourceKbName }}</span>
            <span v-if="detailAnswers[q.id]" class="d-score" :class="judgeClass(detailAnswers[q.id]?.judgment)">
              {{ detailAnswers[q.id]?.score }} 分
            </span>
          </div>
          <div class="d-q">{{ q.question }}</div>
          <div class="d-ua" v-if="detailAnswers[q.id]">
            <span class="lbl">你的答案</span>
            {{ detailAnswers[q.id]?.userAnswer || '（未作答）' }}
          </div>
          <div class="d-ea" v-if="q.expectedAnswer">
            <span class="lbl">标准答案</span>
            {{ q.expectedAnswer }}
          </div>
          <div class="d-fb" v-if="detailAnswers[q.id]">
            <span class="lbl">反馈</span>
            {{ detailAnswers[q.id]?.feedback }}
          </div>
        </article>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  MagicStick, List, DataAnalysis, CircleClose, Trophy,
} from '@element-plus/icons-vue'
import { coachApi, type CoachSession, type CoachQuestion, type CoachAnswer, type UserStats } from '@/api/coach'
import { knowledgeApi, type KnowledgeBase } from '@/api/knowledge'

// ─── KB 列表 ───
const kbList = ref<KnowledgeBase[]>([])
const kbLoading = ref(false)

async function loadKbs() {
  kbLoading.value = true
  try {
    const res: any = await knowledgeApi.list({ current: 1, size: 200 })
    const data = res?.data ?? res
    kbList.value = data?.records || []
  } finally { kbLoading.value = false }
}

// ─── view 切换 ───
type View = 'idle' | 'practice' | 'finished'
const view = ref<View>('idle')

const startForm = reactive({
  kbIds: [] as number[],
  difficulty: 'medium' as 'easy' | 'medium' | 'hard',
  questionTotal: 10,
})

const session = ref<CoachSession | null>(null)
const currentQuestion = ref<CoachQuestion | null>(null)
const lastAnswer = ref<CoachAnswer | null>(null)
const userAnswer = ref<string>('')

const starting = ref(false)
const loadingQuestion = ref(false)
const submitting = ref(false)

// 单题约 1.2 秒、加最少 4 秒底盘
const expectedSeconds = computed(() => Math.max(4, Math.round(startForm.questionTotal * 1.2)))

const questionOptions = computed<string[]>(() => {
  if (!currentQuestion.value?.options) return []
  try {
    const arr = JSON.parse(currentQuestion.value.options)
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
})

const isLastQuestion = computed(() => {
  if (!session.value) return false
  return (session.value.questionDone || 0) >= (session.value.questionTotal || 0)
})

// ─── 启动 ───
async function onStart() {
  starting.value = true
  try {
    const res: any = await coachApi.startSession({
      kbIds: startForm.kbIds.length ? startForm.kbIds : undefined,
      difficulty: startForm.difficulty,
      questionTotal: startForm.questionTotal,
    })
    session.value = res?.data ?? res
    view.value = 'practice'
    await loadNext()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '启动失败')
  } finally { starting.value = false }
}

async function loadNext() {
  if (!session.value) return
  if (isLastQuestion.value) {
    await refreshSession()
    view.value = 'finished'
    return
  }
  loadingQuestion.value = true
  userAnswer.value = ''
  lastAnswer.value = null
  try {
    const res: any = await coachApi.nextQuestion(session.value.id)
    currentQuestion.value = res?.data ?? res
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '出题失败')
  } finally { loadingQuestion.value = false }
}

async function onSubmit() {
  if (!currentQuestion.value) return
  const ans = (typeof userAnswer.value === 'string' ? userAnswer.value : String(userAnswer.value || '')).trim()
  if (!ans) {
    ElMessage.warning('请先填写答案')
    return
  }
  submitting.value = true
  try {
    const res: any = await coachApi.submit(currentQuestion.value.id, ans)
    lastAnswer.value = res?.data ?? res
    // 拉一次最新 session，更新进度
    await refreshSession()
    // 评分完后服务端会把 expected_answer 加入新的 detail 查询；但 currentQuestion 是 next 时拿的，没有 expected
    // 这里通过详情接口补一次（仅本题）
    await fillExpected()
    if (session.value && session.value.status === 'finished') {
      // 自动跳总结
      // 但让用户先看完反馈，按"查看总结"再切
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '提交失败')
  } finally { submitting.value = false }
}

async function fillExpected() {
  if (!session.value || !currentQuestion.value) return
  try {
    const res: any = await coachApi.sessionDetail(session.value.id)
    const data = res?.data ?? res
    const qs: CoachQuestion[] = data?.questions || []
    const matched = qs.find(q => q.id === currentQuestion.value!.id)
    if (matched) {
      currentQuestion.value.expectedAnswer = matched.expectedAnswer
      currentQuestion.value.explanation = matched.explanation
    }
  } catch {}
}

async function refreshSession() {
  if (!session.value) return
  try {
    const res: any = await coachApi.getSession(session.value.id)
    session.value = res?.data ?? res
  } catch {}
}

async function onAbort() {
  if (!session.value) return
  try {
    await ElMessageBox.confirm('退出后本次练习不可继续，确认？', '提示', { type: 'warning' })
    await coachApi.endSession(session.value.id)
    resetToIdle()
  } catch {}
}

function resetToIdle() {
  session.value = null
  currentQuestion.value = null
  lastAnswer.value = null
  userAnswer.value = ''
  view.value = 'idle'
}

// ─── 总结 ───
const avgScore = computed(() => {
  if (!session.value || !session.value.questionDone) return 0
  return Math.round((session.value.totalScore || 0) / session.value.questionDone)
})
const medalColor = computed(() => {
  if (avgScore.value >= 85) return '#f59e0b'
  if (avgScore.value >= 60) return '#38bdf8'
  return '#94a3b8'
})
const summaryMessage = computed(() => {
  if (avgScore.value >= 90) return '非常出色！知识掌握扎实。'
  if (avgScore.value >= 75) return '不错的水平，仍有提升空间。'
  if (avgScore.value >= 60) return '基础已建立，建议复习薄弱章节再练。'
  return '建议先回到资料里把关键点过一遍，再来挑战。'
})

// ─── 历史 ───
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyList = ref<CoachSession[]>([])
async function loadHistory() {
  historyVisible.value = true
  historyLoading.value = true
  try {
    const res: any = await coachApi.mySessions({ current: 1, size: 30 })
    const data = res?.data ?? res
    historyList.value = data?.records || []
  } finally { historyLoading.value = false }
}

async function openHistoryDetail(s: CoachSession) {
  session.value = s
  await openSessionDetail(s.id)
  historyVisible.value = false
  showDetail.value = true
}

// ─── 我的统计 ───
const showStats = ref(false)
const statsLoading = ref(false)
const stats = ref<UserStats | null>(null)
async function loadStats() {
  statsLoading.value = true
  try {
    const res: any = await coachApi.myStats()
    stats.value = res?.data ?? res
  } finally { statsLoading.value = false }
}
const accuracyPct = computed(() => {
  if (!stats.value || !stats.value.accuracy) return '0%'
  return Math.round(stats.value.accuracy * 100) + '%'
})

import { watch } from 'vue'
watch(showStats, v => { if (v) loadStats() })

// ─── 详情 ───
const showDetail = ref(false)
const detailLoading = ref(false)
const detailQuestions = ref<CoachQuestion[]>([])
const detailAnswers = ref<Record<number, CoachAnswer>>({})

async function openSessionDetail(sessionId: number) {
  detailLoading.value = true
  try {
    const res: any = await coachApi.sessionDetail(sessionId)
    const data = res?.data ?? res
    detailQuestions.value = data?.questions || []
    detailAnswers.value = data?.answers || {}
  } finally { detailLoading.value = false }
}

watch(showDetail, async v => {
  if (v && session.value) {
    await openSessionDetail(session.value.id)
  }
})

// ─── helpers ───
function extractChoiceLetter(opt: string): string {
  // "A. xxx" → "A"
  if (!opt) return ''
  const m = opt.match(/^\s*([A-D])[\.、\s]/i)
  if (m && m[1]) return m[1].toUpperCase()
  return opt.charAt(0).toUpperCase()
}

function qTypeLabel(t?: string) {
  return {
    short_answer: '简答题',
    multiple_choice: '选择题',
    true_false: '判断题',
  }[t || ''] || '简答题'
}

function difficultyLabel(d?: string) {
  return { easy: '简单', medium: '中等', hard: '困难' }[d || ''] || '中等'
}

function judgeLabel(j?: string) {
  return { correct: '正确', partial: '部分正确', wrong: '错误' }[j || ''] || ''
}

function judgeClass(j?: string) {
  return {
    'fb-correct': j === 'correct',
    'fb-partial': j === 'partial',
    'fb-wrong': j === 'wrong',
  }
}

function statusLabel(s?: string) {
  return { active: '进行中', finished: '已完成', abandoned: '已退出' }[s || ''] || s
}

function statusTagType(s?: string): any {
  return { active: 'primary', finished: 'success', abandoned: 'info' }[s || ''] || ''
}

function formatTime(t?: string) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  loadKbs()
})
</script>

<style scoped>
.coach-page {
  padding: 28px 32px 48px;
  height: 100%;
  overflow-y: auto;
  background: var(--bg-page);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 28px;
  flex-wrap: wrap;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #ede9fe, #ddd6fe); color: #6d28d9;
}
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }
.header-actions { display: flex; gap: 8px; }

/* ── start card ── */
.start-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 36px 40px;
  max-width: 640px;
  margin: 24px auto;
  box-shadow: var(--shadow-sm);
}
.start-icon {
  width: 78px; height: 78px; margin: 0 auto 14px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #ede9fe, #ddd6fe);
  border-radius: 50%;
}
.start-card h3 { text-align: center; font-size: 20px; font-weight: 700; margin-bottom: 6px; color: var(--ink-1); }
.start-card .hint { text-align: center; font-size: 13px; color: var(--ink-3); margin-bottom: 26px; }
.start-card .prepare-hint {
  text-align: center; font-size: 12px; color: var(--ink-4);
  margin-top: 12px; line-height: 1.6;
}
.form-row { margin-bottom: 18px; }
.form-row label { display: block; font-size: 12.5px; color: var(--ink-2); font-weight: 600; margin-bottom: 8px; }

/* ── practice ── */
.practice-area { max-width: 820px; margin: 0 auto; }
.session-banner {
  display: flex; justify-content: space-between; align-items: flex-end; gap: 16px;
  background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px;
  padding: 14px 20px; margin-bottom: 16px;
}
.session-banner > div:first-child { flex: 1; }
.banner-title {
  display: flex; align-items: center; gap: 10px;
  font-size: 13.5px; color: var(--ink-2); font-weight: 600; margin-bottom: 8px;
}
.banner-scope { color: var(--ink-4); font-weight: 400; }

.q-card {
  background: var(--bg-surface); border: 1px solid var(--line); border-radius: 14px;
  padding: 24px 28px; min-height: 280px;
}
.q-head {
  display: flex; gap: 10px; align-items: center;
  font-size: 11.5px; color: var(--ink-4); margin-bottom: 14px;
  font-family: 'JetBrains Mono', monospace;
}
.q-head .seq { font-weight: 700; color: var(--brand); }
.q-head .q-type {
  padding: 2px 8px; border-radius: 999px;
  background: rgba(124, 58, 237, 0.1); color: #7c3aed; font-weight: 600;
}
.q-head .q-source { margin-left: auto; font-style: italic; }

.q-stem {
  font-size: 15.5px; font-weight: 600; color: var(--ink-1);
  margin-bottom: 18px; line-height: 1.7;
}
.q-options { margin-bottom: 22px; }
.q-options .opt { display: block; margin-bottom: 10px; font-size: 14px; }
.q-options.tf { display: flex; gap: 10px; }

/* feedback */
.feedback {
  border-radius: 10px; padding: 16px 18px; margin-bottom: 18px;
  border-left: 4px solid;
}
.feedback.fb-correct { background: rgba(52, 211, 153, 0.07); border-color: #34d399; }
.feedback.fb-partial { background: rgba(245, 158, 11, 0.07); border-color: #f59e0b; }
.feedback.fb-wrong   { background: rgba(239, 68, 68, 0.07);  border-color: #ef4444; }

.fb-head { display: flex; gap: 10px; align-items: baseline; margin-bottom: 8px; }
.fb-score { font-size: 22px; font-weight: 800; color: var(--ink-1); }
.fb-judge { font-size: 13px; font-weight: 600; color: var(--ink-2); }
.fb-text { font-size: 13.5px; color: var(--ink-2); line-height: 1.7; margin-bottom: 10px; }
.fb-expected, .fb-explain {
  font-size: 12.5px; color: var(--ink-3); line-height: 1.7;
  background: rgba(255,255,255,0.5); padding: 8px 10px; border-radius: 6px; margin-bottom: 6px;
}
.fb-expected .lbl, .fb-explain .lbl {
  display: inline-block; margin-right: 6px; padding: 1px 7px; border-radius: 4px;
  background: var(--bg-hover); color: var(--ink-2); font-weight: 600; font-size: 11px;
}

.q-actions { text-align: right; }

/* ── summary ── */
.summary-card {
  max-width: 540px; margin: 60px auto; text-align: center;
  background: var(--bg-surface); border: 1px solid var(--line); border-radius: 16px;
  padding: 50px 40px;
}
.summary-medal {
  width: 100px; height: 100px; margin: 0 auto 16px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #fef3c7, #fde68a); border-radius: 50%;
}
.summary-card h3 { font-size: 22px; font-weight: 700; color: var(--ink-1); margin-bottom: 28px; }
.summary-stats { display: flex; justify-content: center; gap: 50px; margin-bottom: 22px; }
.summary-stats .stat { display: flex; flex-direction: column; align-items: center; }
.summary-stats .num { font-size: 30px; font-weight: 800; color: var(--brand); }
.summary-stats .lbl { font-size: 12px; color: var(--ink-3); margin-top: 4px; }
.summary-msg { color: var(--ink-2); font-size: 14px; margin-bottom: 26px; }
.summary-actions { display: flex; gap: 10px; justify-content: center; }

/* ── history drawer ── */
.history-list { display: flex; flex-direction: column; gap: 12px; padding: 0 4px; }
.history-item {
  border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px;
  cursor: pointer; transition: all 0.15s;
}
.history-item:hover { border-color: var(--brand); background: var(--bg-hover); }
.history-item .row1 { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--ink-3); margin-bottom: 6px; }
.history-item .row1 .time { margin-left: auto; }
.history-item .row1 .diff { color: var(--ink-4); }
.history-item .row2 { font-size: 13px; color: var(--ink-2); font-weight: 600; margin-bottom: 4px; }
.history-item .row3 { font-size: 11.5px; color: var(--ink-4); display: flex; gap: 6px; }
.history-item .dot { color: var(--line); }

/* ── stats drawer ── */
.stats-panel { padding: 4px; }
.stat-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 26px;
}
.stat-card {
  border: 1px solid var(--line); border-radius: 10px; padding: 16px 18px;
  background: var(--bg-surface);
}
.stat-card .card-label { font-size: 11.5px; color: var(--ink-4); font-weight: 600; }
.stat-card .card-num { font-size: 28px; font-weight: 800; color: var(--ink-1); margin: 6px 0 2px; }
.stat-card .card-sub { font-size: 11px; color: var(--ink-4); }

.stats-sub { font-size: 13px; font-weight: 700; color: var(--ink-2); margin: 14px 0 12px; }
.weak-list { display: flex; flex-direction: column; gap: 10px; }
.weak-item { font-size: 12.5px; color: var(--ink-2); }
.weak-name { margin-bottom: 4px; font-weight: 600; }
.weak-bar { height: 6px; background: var(--bg-hover); border-radius: 3px; overflow: hidden; }
.weak-fill { height: 100%; background: linear-gradient(90deg, #ef4444, #f59e0b 60%, #34d399); }
.weak-score { font-size: 11px; color: var(--ink-4); margin-top: 3px; }

/* ── detail ── */
.detail-list { max-height: 70vh; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.detail-item {
  border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px;
}
.d-head {
  display: flex; gap: 8px; align-items: center;
  font-size: 11.5px; color: var(--ink-4); margin-bottom: 8px;
}
.d-head .d-seq { font-weight: 700; color: var(--brand); }
.d-head .d-type {
  padding: 1px 7px; border-radius: 4px;
  background: rgba(124, 58, 237, 0.1); color: #7c3aed; font-weight: 600;
}
.d-head .d-source { margin-left: auto; font-style: italic; }
.d-head .d-score { padding: 1px 8px; border-radius: 4px; font-weight: 700; }
.d-head .d-score.fb-correct { background: rgba(52, 211, 153, 0.15); color: #047857; }
.d-head .d-score.fb-partial { background: rgba(245, 158, 11, 0.15); color: #b45309; }
.d-head .d-score.fb-wrong { background: rgba(239, 68, 68, 0.15); color: #b91c1c; }

.d-q { font-size: 13.5px; font-weight: 600; color: var(--ink-1); margin-bottom: 8px; }
.d-ua, .d-ea, .d-fb { font-size: 12.5px; color: var(--ink-2); line-height: 1.7; margin-bottom: 4px; }
.d-ua .lbl, .d-ea .lbl, .d-fb .lbl {
  display: inline-block; margin-right: 6px; padding: 1px 7px; border-radius: 4px;
  background: var(--bg-hover); color: var(--ink-3); font-weight: 600; font-size: 11px;
}

.empty { padding: 40px 0; text-align: center; color: var(--ink-3); }
</style>
