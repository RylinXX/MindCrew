package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.*;
import com.simon.MindCrew.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 教练模式服务 · 任务 9（v2 · 反幻觉强化）
 *
 * 流程：
 *   1. startSession(userId, kbIds, difficulty, total)
 *        - ACL 校验 kbIds 是否可访问
 *        - 一次性预抽 N 个不重复 chunks
 *        - 一次 LLM 大调用产出 N 道题，每题必须带 sourceQuote
 *        - 后处理校验：sourceQuote 必须是源 chunk 的真子串（反幻觉）
 *        - 不合格的题剔除 → 若数量不够，对剩余 chunks 单题补救
 *        - 全部 N 道题入库后才返回 session（用户启动后无中途等待）
 *   2. nextQuestion(sessionId, userId)
 *        - 纯 DB 查 seq = questionDone + 1 的题 · 瞬时返回
 *   3. submitAnswer / endSession / 统计 · 不变
 *
 * 反幻觉策略：
 *   - 每道题强制 1:1 绑定一个原文 chunk
 *   - LLM 输出 sourceQuote → 与 chunk 做"归一化包含校验"
 *   - 不通过 = 视为幻觉题，整题丢弃，不进库
 *   - prompt 红线明确：禁止编造原文外的事实
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachService {

    private final CoachSessionMapper sessionMapper;
    private final CoachQuestionMapper questionMapper;
    private final CoachAnswerMapper answerMapper;
    private final KbChunkMapper chunkMapper;
    private final KbKnowledgeBaseMapper kbMapper;
    private final KbAclService kbAclService;
    private final AiConfigHolder aiConfigHolder;
    private final UsageStatsService usageStatsService;

    private static final int CHUNK_MIN_LEN = 120;     // 太短的 chunk 不出题
    private static final int CHUNK_MAX_LEN = 1500;    // 单 chunk 截断（控制 prompt 总长，N=10 时约 15K 字符）
    private static final int RANDOM_PICK_POOL = 30;   // 随机抽取候选池大小
    private static final Random RND = new Random();

    // ─────────────────────────────────────────────
    // session
    // ─────────────────────────────────────────────

    @Transactional
    public CoachSession startSession(Long userId, List<Long> kbIds, String difficulty, Integer total) {
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");

        // ACL：用户实际可访问的 KB 集合
        List<Long> accessible = kbAclService.listAccessibleKbIds(userId);
        if (accessible.isEmpty()) {
            throw new IllegalStateException("当前账号没有可访问的知识库，无法启动教练模式");
        }

        List<Long> finalKbIds;
        if (kbIds == null || kbIds.isEmpty()) {
            finalKbIds = accessible;
        } else {
            finalKbIds = kbIds.stream().filter(accessible::contains).distinct().toList();
            if (finalKbIds.isEmpty()) {
                throw new IllegalStateException("选定的知识库不在你的访问权限内");
            }
        }

        String diff = normalizeDifficulty(difficulty);
        int t = total == null || total <= 0 ? 10 : Math.min(total, 50);

        // 1) 预抽 N+2 个候选 chunk（多抽一点，给后处理剔除留余量）
        int candidateNeed = t + 2;
        List<KbChunk> chunks = pickDistinctChunks(finalKbIds, candidateNeed);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("选定的知识库暂无足够素材出题，请换一个范围或增加文档");
        }
        if (chunks.size() < t) {
            log.info("[Coach] 可用素材不足 · 期望 {} 实际 {}", t, chunks.size());
        }

        // 2) 一次大调用，让 LLM 基于 N 个 chunks 出 N 道题
        List<GeneratedQuestion> raw = generateBatchQuestions(chunks, diff);

        // 3) 反幻觉校验：sourceQuote 必须命中源 chunk
        List<GeneratedQuestion> validated = new ArrayList<>();
        for (GeneratedQuestion gq : raw) {
            if (gq.sourceIndex < 0 || gq.sourceIndex >= chunks.size()) {
                log.warn("[Coach] 丢弃题 · sourceIndex 越界: {}", gq.sourceIndex);
                continue;
            }
            KbChunk src = chunks.get(gq.sourceIndex);
            if (!validateSourceQuote(gq.sourceQuote, src.getContent())) {
                log.warn("[Coach] 丢弃题 · sourceQuote 不在原文中: question={} quote={}",
                        gq.question, abbreviate(gq.sourceQuote, 60));
                continue;
            }
            validated.add(gq);
            if (validated.size() >= t) break;
        }

        if (validated.isEmpty()) {
            throw new IllegalStateException("LLM 生成的题目都没通过反幻觉校验，请稍后重试或换知识库");
        }

        int actualTotal = validated.size();

        // 4) 创建 session
        CoachSession s = new CoachSession();
        s.setUserId(userId);
        s.setKbIds(JSON.toJSONString(finalKbIds));
        s.setKbScopeLabel(buildKbScopeLabel(finalKbIds));
        s.setDifficulty(diff);
        s.setQuestionTotal(actualTotal);
        s.setQuestionDone(0);
        s.setCorrectCount(0);
        s.setTotalScore(0);
        s.setStatus("active");
        s.setStartAt(LocalDateTime.now());
        sessionMapper.insert(s);

        // 5) 批量写题
        Map<Long, String> kbNameCache = new HashMap<>();
        for (int i = 0; i < validated.size(); i++) {
            GeneratedQuestion gq = validated.get(i);
            KbChunk src = chunks.get(gq.sourceIndex);
            String kbName = kbNameCache.computeIfAbsent(src.getKbId(), kid -> {
                KbKnowledgeBase kb = kbMapper.selectById(kid);
                return kb == null ? "(已删除)" : kb.getName();
            });

            CoachQuestion q = new CoachQuestion();
            q.setSessionId(s.getId());
            q.setSeq(i + 1);
            q.setQuestion(gq.question);
            q.setQuestionType(gq.questionType);
            q.setOptions(gq.options == null ? null : JSON.toJSONString(gq.options));
            q.setExpectedAnswer(gq.expectedAnswer);
            q.setExplanation(gq.explanation);
            q.setSourceChunkId(src.getId());
            q.setSourceKbId(src.getKbId());
            q.setSourceKbName(kbName);
            q.setSourceQuote(gq.sourceQuote);
            q.setDifficulty(diff);
            questionMapper.insert(q);
        }

        log.info("[Coach] startSession · user={} kbScope={} diff={} target={} actual={}",
                userId, finalKbIds, diff, t, actualTotal);
        return s;
    }

    @Transactional
    public CoachSession endSession(Long sessionId, Long userId) {
        CoachSession s = mustSession(sessionId, userId);
        if (!"active".equals(s.getStatus())) return s;
        s.setStatus("finished");
        s.setEndAt(LocalDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    public CoachSession getSession(Long sessionId, Long userId) {
        return mustSession(sessionId, userId);
    }

    private CoachSession mustSession(Long sessionId, Long userId) {
        CoachSession s = sessionMapper.selectById(sessionId);
        if (s == null) throw new IllegalArgumentException("session 不存在: " + sessionId);
        if (!Objects.equals(s.getUserId(), userId)) {
            throw new IllegalStateException("无权访问他人的练习会话");
        }
        return s;
    }

    // ─────────────────────────────────────────────
    // 出题
    // ─────────────────────────────────────────────

    /**
     * 现在仅从 DB 返回预生成的下一道题。瞬时返回，无 LLM 调用。
     */
    public CoachQuestion nextQuestion(Long sessionId, Long userId) {
        CoachSession s = mustSession(sessionId, userId);
        if (!"active".equals(s.getStatus())) {
            throw new IllegalStateException("会话已结束，请新建一次");
        }
        int nextSeq = (s.getQuestionDone() == null ? 0 : s.getQuestionDone()) + 1;
        if (nextSeq > s.getQuestionTotal()) {
            throw new IllegalStateException("已答完，请点结束查看总结");
        }
        CoachQuestion q = questionMapper.selectOne(new LambdaQueryWrapper<CoachQuestion>()
                .eq(CoachQuestion::getSessionId, sessionId)
                .eq(CoachQuestion::getSeq, nextSeq)
                .last("LIMIT 1"));
        if (q == null) {
            throw new IllegalStateException("题目 #" + nextSeq + " 不存在（数据异常）");
        }
        return q;
    }

    /**
     * 跨多个 KB 抽取 N 个不重复 chunks。
     * 策略：先按 KB 加权（chunks 多的 KB 抽概率高），每个 KB 内随机 offset。
     */
    private List<KbChunk> pickDistinctChunks(List<Long> kbIds, int need) {
        // 1) 收集每个 KB 的 chunk 数量
        Map<Long, Long> kbCounts = new LinkedHashMap<>();
        long grandTotal = 0;
        for (Long kbId : kbIds) {
            Long c = chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                    .eq(KbChunk::getKbId, kbId));
            if (c != null && c > 0) {
                kbCounts.put(kbId, c);
                grandTotal += c;
            }
        }
        if (grandTotal == 0) return List.of();

        // 2) 为每个 KB 分配抽取配额：count / grandTotal * need，但每个 KB 至少 1（如可能）
        Map<Long, Integer> quota = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : kbCounts.entrySet()) {
            int q = Math.max(1, (int) Math.round((double) e.getValue() / grandTotal * need));
            quota.put(e.getKey(), q);
        }

        // 3) 实际从每个 KB 拉一批候选，再随机挑
        Set<Long> usedIds = new HashSet<>();
        List<KbChunk> picked = new ArrayList<>();

        // pool size 取 quota * 2 与 RANDOM_PICK_POOL 较大值（每 KB 至少有 20 个候选）
        for (Map.Entry<Long, Integer> e : quota.entrySet()) {
            Long kbId = e.getKey();
            int wantFromThisKb = e.getValue();
            long total = kbCounts.get(kbId);

            // 随机起点抽一段
            int poolSize = Math.max(RANDOM_PICK_POOL, wantFromThisKb * 3);
            int maxOffset = (int) Math.max(0, total - poolSize);
            int offset = maxOffset == 0 ? 0 : RND.nextInt(maxOffset + 1);
            int current = offset / poolSize + 1;

            Page<KbChunk> page = new Page<>(current, poolSize);
            page.setSearchCount(false);
            IPage<KbChunk> r = chunkMapper.selectPage(page, new LambdaQueryWrapper<KbChunk>()
                    .eq(KbChunk::getKbId, kbId)
                    .orderByAsc(KbChunk::getId));
            List<KbChunk> pool = r.getRecords().stream()
                    .filter(c -> c.getContent() != null && c.getContent().length() >= CHUNK_MIN_LEN)
                    .collect(Collectors.toList());
            Collections.shuffle(pool, RND);

            int got = 0;
            for (KbChunk c : pool) {
                if (usedIds.contains(c.getId())) continue;
                if (got >= wantFromThisKb) break;
                if (c.getContent().length() > CHUNK_MAX_LEN) {
                    c.setContent(c.getContent().substring(0, CHUNK_MAX_LEN));
                }
                picked.add(c);
                usedIds.add(c.getId());
                got++;
            }

            if (picked.size() >= need) break;
        }

        // 4) 如果还不够，再从已扫过的 KB 再抓一轮（去 used）
        if (picked.size() < need) {
            for (Long kbId : kbCounts.keySet()) {
                if (picked.size() >= need) break;
                IPage<KbChunk> r = chunkMapper.selectPage(new Page<>(1, 200),
                        new LambdaQueryWrapper<KbChunk>()
                                .eq(KbChunk::getKbId, kbId)
                                .orderByAsc(KbChunk::getId));
                List<KbChunk> all = r.getRecords().stream()
                        .filter(c -> c.getContent() != null && c.getContent().length() >= CHUNK_MIN_LEN)
                        .filter(c -> !usedIds.contains(c.getId()))
                        .collect(Collectors.toList());
                Collections.shuffle(all, RND);
                for (KbChunk c : all) {
                    if (picked.size() >= need) break;
                    if (c.getContent().length() > CHUNK_MAX_LEN) {
                        c.setContent(c.getContent().substring(0, CHUNK_MAX_LEN));
                    }
                    picked.add(c);
                    usedIds.add(c.getId());
                }
            }
        }

        return picked;
    }

    /** 并发单题出题：N 个 chunks 启动 N 个 LLM 调用，CompletableFuture.allOf 等结果 */
    private static final java.util.concurrent.ExecutorService COACH_LLM_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(12, r -> {
                Thread t = new Thread(r, "coach-llm");
                t.setDaemon(true);
                return t;
            });
    private static final long PER_QUESTION_TIMEOUT_SEC = 45;

    /**
     * 改造点（性能优化 A）：
     * 原来一次大 LLM 调用出 N 题（30-60s），改为 N 个并发单题调用（4-8s）
     * - 每题独立 prompt，prompt 短，LLM 响应快
     * - CompletableFuture.allOf 全部 ready 后聚合
     * - 单题失败不阻塞其他题
     * - sourceIndex 直接用循环索引赋值（不依赖 LLM 输出），更稳
     */
    private List<GeneratedQuestion> generateBatchQuestions(List<KbChunk> chunks, String difficulty) {
        String diffHint = switch (difficulty) {
            case "easy"   -> "题目应侧重事实复述，答案在原文中能直接找到。";
            case "hard"   -> "题目应需要综合分析或推断，但所有事实仍必须出自原文，不可推断之外的事。";
            default       -> "题目应需要少量理解或归纳，所有事实必须出自原文。";
        };

        Map<Long, String> kbNameCache = new HashMap<>();
        List<java.util.concurrent.CompletableFuture<GeneratedQuestion>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (int i = 0; i < chunks.size(); i++) {
            final int idx = i;
            final KbChunk chunk = chunks.get(i);
            final String kbName = kbNameCache.computeIfAbsent(chunk.getKbId(), kid -> {
                KbKnowledgeBase kb = kbMapper.selectById(kid);
                return kb == null ? "(已删除)" : kb.getName();
            });

            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    GeneratedQuestion g = generateSingleQuestion(chunk.getContent(), difficulty, diffHint, kbName);
                    if (g != null) g.sourceIndex = idx;
                    return g;
                } catch (Exception e) {
                    log.warn("[Coach] 单题失败 idx={} err={}", idx, e.getMessage());
                    return null;
                }
            }, COACH_LLM_POOL));
        }

        // 等所有题完成（带整体超时保护）
        try {
            java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(PER_QUESTION_TIMEOUT_SEC * 2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("[Coach] 批量出题整体超时，已完成的将被采用");
        } catch (Exception e) {
            log.warn("[Coach] 批量出题等待失败: {}", e.getMessage());
        }

        List<GeneratedQuestion> out = new ArrayList<>();
        for (java.util.concurrent.CompletableFuture<GeneratedQuestion> f : futures) {
            try {
                if (f.isDone() && !f.isCancelled()) {
                    GeneratedQuestion g = f.get(0, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (g != null) out.add(g);
                }
            } catch (Exception ignored) {}
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[Coach] 并行出题 · chunks={} success={} elapsed={}ms", chunks.size(), out.size(), elapsed);
        return out;
    }

    /** 单题 LLM 调用 · 用 qwen-turbo 加速（如果 active 是 plus 也接受，但推荐 turbo） */
    private GeneratedQuestion generateSingleQuestion(String chunkText, String difficulty,
                                                     String diffHint, String kbName) {
        String system = """
                你是企业知识库的「教练」。基于一段企业内部资料给员工出一道考核题。

                ⚠️ 红线（违反则视为出错）：
                  1. 题目只能基于下方提供的「原文」，禁止编造原文外的事实、人名、数字、流程。
                  2. 标准答案必须能在原文中找到依据。
                  3. 必须输出 sourceQuote（原文里你用来出题的句子或段落，原样照抄 15-80 字）。

                严格按 JSON 输出，不要 markdown 包裹。
                """;
        String user = """
                【难度】%s
                【难度说明】%s
                【来源知识库】%s
                【原文】
                %s

                请输出 JSON：
                {
                  "sourceQuote": "你直接从原文中复制的 15-80 字片段",
                  "questionType": "short_answer | multiple_choice | true_false（3 选 1，按原文适合的形式）",
                  "question": "题干（中文）",
                  "options": ["A. xx","B. xx","C. xx","D. xx"] 或 null,
                  "expectedAnswer": "标准答案（短答 1-3 句；选择题填字母；判断填\\"对\\"或\\"错\\"）",
                  "explanation": "30-80 字解析，必须基于原文"
                }

                只输出 JSON 对象本身。
                """.formatted(difficulty, diffHint, kbName, chunkText);

        ChatResponse resp = aiConfigHolder.getChatModel().call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        String raw = resp.getResult().getOutput().getText();
        return parseQuestionObject(raw);
    }

    private GeneratedQuestion parseQuestionObject(String raw) {
        String cleaned = stripToJsonObject(raw);
        JSONObject o;
        try { o = JSON.parseObject(cleaned); }
        catch (Exception e) {
            log.warn("[Coach] 单题 JSON 解析失败 raw={}", abbreviate(raw, 200));
            return null;
        }
        GeneratedQuestion g = new GeneratedQuestion();
        g.sourceQuote = o.getString("sourceQuote");
        g.questionType = Optional.ofNullable(o.getString("questionType"))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).orElse("short_answer");
        if (!List.of("short_answer", "multiple_choice", "true_false").contains(g.questionType)) {
            g.questionType = "short_answer";
        }
        g.question = o.getString("question");
        g.expectedAnswer = o.getString("expectedAnswer");
        g.explanation = o.getString("explanation");
        JSONArray opts = o.getJSONArray("options");
        if (opts != null && !opts.isEmpty()) {
            g.options = new ArrayList<>();
            for (int j = 0; j < opts.size(); j++) {
                String s = opts.getString(j);
                if (s != null && !s.isBlank()) g.options.add(s.trim());
            }
        }
        if ("multiple_choice".equals(g.questionType)
                && (g.options == null || g.options.size() < 2)) {
            g.questionType = "short_answer";
            g.options = null;
        }
        if (g.question == null || g.question.isBlank()
                || g.expectedAnswer == null || g.expectedAnswer.isBlank()
                || g.sourceQuote == null || g.sourceQuote.isBlank()) {
            return null;
        }
        return g;
    }

    private String stripToJsonObject(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s >= 0 && e > s) return t.substring(s, e + 1);
        return t;
    }

    private List<GeneratedQuestion> parseQuestionArray(String raw) {
        String cleaned = stripToJsonArray(raw);
        JSONArray arr;
        try {
            arr = JSON.parseArray(cleaned);
        } catch (Exception e) {
            log.warn("[Coach] 批量出题 JSON 解析失败 raw={}", raw);
            throw new IllegalStateException("LLM 输出格式不合规，请稍后重试");
        }
        List<GeneratedQuestion> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o == null) continue;
            try {
                GeneratedQuestion g = new GeneratedQuestion();
                g.sourceIndex = o.getIntValue("sourceIndex", -1);
                g.sourceQuote = o.getString("sourceQuote");
                g.questionType = Optional.ofNullable(o.getString("questionType"))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT)).orElse("short_answer");
                if (!List.of("short_answer", "multiple_choice", "true_false").contains(g.questionType)) {
                    g.questionType = "short_answer";
                }
                g.question = o.getString("question");
                g.expectedAnswer = o.getString("expectedAnswer");
                g.explanation = o.getString("explanation");
                JSONArray opts = o.getJSONArray("options");
                if (opts != null && !opts.isEmpty()) {
                    g.options = new ArrayList<>();
                    for (int j = 0; j < opts.size(); j++) {
                        String s = opts.getString(j);
                        if (s != null && !s.isBlank()) g.options.add(s.trim());
                    }
                }
                if ("multiple_choice".equals(g.questionType)
                        && (g.options == null || g.options.size() < 2)) {
                    g.questionType = "short_answer";
                    g.options = null;
                }
                // 必填字段缺失 → 丢弃
                if (g.question == null || g.question.isBlank()
                        || g.expectedAnswer == null || g.expectedAnswer.isBlank()
                        || g.sourceQuote == null || g.sourceQuote.isBlank()) {
                    log.warn("[Coach] 丢弃题 · 必填字段缺失: {}", o);
                    continue;
                }
                out.add(g);
            } catch (Exception e) {
                log.warn("[Coach] 跳过解析失败的题: err={} item={}", e.getMessage(), o);
            }
        }
        return out;
    }

    /**
     * 反幻觉校验：sourceQuote 必须是 chunk 内容的子串（归一化后包含校验）。
     *
     * 归一化：去掉所有空白字符、全角空格、引号等，保留实际字符。
     */
    private boolean validateSourceQuote(String quote, String chunkContent) {
        if (quote == null || quote.isBlank() || chunkContent == null) return false;
        String nq = normalizeForMatch(quote);
        String nc = normalizeForMatch(chunkContent);
        if (nq.length() < 6) return false;             // 过短不算证据
        if (nc.contains(nq)) return true;
        // 退而求其次：60% 长度的开头能命中 → 也认（LLM 可能少抄了几字）
        int head = Math.max(6, (int) (nq.length() * 0.6));
        return nc.contains(nq.substring(0, head));
    }

    private String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u3000\"'`「」『』《》()（）\\[\\]【】、，,。.！!？?；;：:]", "");
    }

    private String stripToJsonArray(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("```")) {
            int s = t.indexOf('[');
            int e = t.lastIndexOf(']');
            if (s >= 0 && e > s) return t.substring(s, e + 1);
        }
        // 普通情况
        int s = t.indexOf('[');
        int e = t.lastIndexOf(']');
        if (s >= 0 && e > s) return t.substring(s, e + 1);
        return t;
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─────────────────────────────────────────────
    // 评分
    // ─────────────────────────────────────────────

    @Transactional
    public CoachAnswer submitAnswer(Long questionId, Long userId, String userAnswer) {
        if (userAnswer == null) userAnswer = "";
        CoachQuestion q = questionMapper.selectById(questionId);
        if (q == null) throw new IllegalArgumentException("题目不存在: " + questionId);

        CoachSession s = mustSession(q.getSessionId(), userId);
        if (!"active".equals(s.getStatus())) {
            throw new IllegalStateException("会话已结束，无法继续提交答案");
        }

        // 同一题已答过 → 直接返回旧记录（幂等）
        CoachAnswer exists = answerMapper.selectOne(new LambdaQueryWrapper<CoachAnswer>()
                .eq(CoachAnswer::getQuestionId, questionId));
        if (exists != null) {
            return exists;
        }

        Judgement j = judge(q, userAnswer);

        // 任务 13 · 记账
        try {
            int inToks = estimateTokens(q.getQuestion()) + estimateTokens(q.getExpectedAnswer())
                    + estimateTokens(userAnswer) + 200;
            int outToks = estimateTokens(j.feedback) + 50;
            usageStatsService.recordChatAsync(userId, getActiveModelName(), inToks, outToks, false);
        } catch (Exception ignored) {}

        CoachAnswer ans = new CoachAnswer();
        ans.setQuestionId(questionId);
        ans.setSessionId(q.getSessionId());
        ans.setUserId(userId);
        ans.setUserAnswer(userAnswer);
        ans.setScore(j.score);
        ans.setJudgment(j.judgment);
        ans.setFeedback(j.feedback);
        // 推荐复习章节：当前 chunk + LLM 推荐的相邻章节（简化为同 KB 同 chunk_index 邻居）
        ans.setRecommendChunkIds(JSON.toJSONString(recommendChunks(q, j.score)));
        ans.setAnswerAt(LocalDateTime.now());
        answerMapper.insert(ans);

        // 更新 session 进度
        s.setQuestionDone(s.getQuestionDone() + 1);
        s.setTotalScore(s.getTotalScore() + j.score);
        if (j.score >= 80) s.setCorrectCount(s.getCorrectCount() + 1);
        if (s.getQuestionDone() >= s.getQuestionTotal()) {
            s.setStatus("finished");
            s.setEndAt(LocalDateTime.now());
        }
        sessionMapper.updateById(s);

        return ans;
    }

    private Judgement judge(CoachQuestion q, String userAnswer) {
        // 判断题 / 选择题做规则前置匹配 · 模型只做模糊兜底
        if ("true_false".equals(q.getQuestionType())) {
            String norm = normalizeTrueFalse(userAnswer);
            String exp  = normalizeTrueFalse(q.getExpectedAnswer());
            if (!norm.isBlank() && !exp.isBlank()) {
                boolean ok = norm.equals(exp);
                return new Judgement(ok ? 100 : 0, ok ? "correct" : "wrong",
                        ok ? "完全正确。" : ("正确答案是「" + q.getExpectedAnswer() + "」。" +
                                (q.getExplanation() == null ? "" : "\n" + q.getExplanation())));
            }
        }
        if ("multiple_choice".equals(q.getQuestionType())) {
            String norm = normalizeChoice(userAnswer);
            String exp  = normalizeChoice(q.getExpectedAnswer());
            if (!norm.isBlank() && !exp.isBlank()) {
                boolean ok = norm.equals(exp);
                return new Judgement(ok ? 100 : 0, ok ? "correct" : "wrong",
                        ok ? "完全正确。" : ("正确答案是「" + q.getExpectedAnswer() + "」。" +
                                (q.getExplanation() == null ? "" : "\n" + q.getExplanation())));
            }
        }

        // 短答题 → LLM 评分
        String system = """
                你是企业知识库的「评卷老师」。请基于「标准答案」对「学员答案」打分。
                打分原则：
                  - 0-100 分，整数。
                  - >=80 视为正确，60-79 部分正确，<60 错误。
                  - 严格忠于标准答案的事实点；学员答案与事实矛盾的扣分。
                  - 不要无条件夸奖；事实错就指出错在哪。
                  - 反馈用中文，不要用 markdown 表头，1-3 句话。
                严格按 JSON 输出。
                """;
        String user = """
                【题目】%s
                【标准答案】%s
                【学员答案】%s
                【出题解析】%s

                请输出 JSON：
                {
                  "score": 0-100 整数,
                  "judgment": "correct | partial | wrong",
                  "feedback": "1-3 句话告诉学员对在哪 / 差什么 / 应当怎么改进。"
                }
                只输出 JSON，不要 markdown。
                """.formatted(
                q.getQuestion(),
                q.getExpectedAnswer(),
                userAnswer == null || userAnswer.isBlank() ? "（学员未作答）" : userAnswer,
                q.getExplanation() == null ? "" : q.getExplanation()
        );

        ChatResponse resp = aiConfigHolder.getChatModel().call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        String raw = resp.getResult().getOutput().getText();
        JSONObject o;
        try {
            o = JSON.parseObject(stripCodeFence(raw));
        } catch (Exception e) {
            log.warn("[Coach] 评分 JSON 解析失败 raw={}", raw);
            throw new IllegalStateException("评分失败，请重试");
        }
        int score = Math.max(0, Math.min(100, o.getIntValue("score")));
        String judgment = o.getString("judgment");
        if (judgment == null || !List.of("correct", "partial", "wrong").contains(judgment)) {
            judgment = score >= 80 ? "correct" : (score >= 60 ? "partial" : "wrong");
        }
        String feedback = o.getString("feedback");
        if (feedback == null || feedback.isBlank()) {
            feedback = score >= 80 ? "回答正确。" : "回答与标准答案有差距，建议复习原文。";
        }
        return new Judgement(score, judgment, feedback);
    }

    private List<Long> recommendChunks(CoachQuestion q, int score) {
        // score >= 80 不推荐复习；其他场景推荐当前 chunk + 同 KB 同序号附近 chunk
        if (score >= 80) return List.of();
        List<Long> ids = new ArrayList<>();
        if (q.getSourceChunkId() != null) ids.add(q.getSourceChunkId());

        if (q.getSourceKbId() != null) {
            // 取来源 chunk 的 chunk_index
            KbChunk src = chunkMapper.selectById(q.getSourceChunkId());
            if (src != null && src.getChunkIndex() != null) {
                Integer ci = src.getChunkIndex();
                List<KbChunk> nb = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getKbId, q.getSourceKbId())
                        .between(KbChunk::getChunkIndex, Math.max(0, ci - 1), ci + 1)
                        .ne(KbChunk::getId, q.getSourceChunkId())
                        .last("LIMIT 2"));
                nb.stream().map(KbChunk::getId).forEach(ids::add);
            }
        }
        return ids;
    }

    // ─────────────────────────────────────────────
    // 历史 / 统计
    // ─────────────────────────────────────────────

    public IPage<CoachSession> userSessions(Long userId, int current, int size) {
        return sessionMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<CoachSession>()
                        .eq(CoachSession::getUserId, userId)
                        .orderByDesc(CoachSession::getStartAt));
    }

    public List<CoachQuestion> sessionQuestions(Long sessionId, Long userId) {
        mustSession(sessionId, userId);
        return questionMapper.selectList(new LambdaQueryWrapper<CoachQuestion>()
                .eq(CoachQuestion::getSessionId, sessionId)
                .orderByAsc(CoachQuestion::getSeq));
    }

    public Map<Long, CoachAnswer> sessionAnswers(Long sessionId) {
        List<CoachAnswer> answers = answerMapper.selectList(new LambdaQueryWrapper<CoachAnswer>()
                .eq(CoachAnswer::getSessionId, sessionId));
        Map<Long, CoachAnswer> map = new HashMap<>();
        for (CoachAnswer a : answers) map.put(a.getQuestionId(), a);
        return map;
    }

    /** 个人统计：总 session / 总题 / 平均分 / 正确率 / 薄弱 KB */
    public Map<String, Object> userStats(Long userId) {
        List<CoachSession> all = sessionMapper.selectList(new LambdaQueryWrapper<CoachSession>()
                .eq(CoachSession::getUserId, userId));
        long sessionCount = all.size();
        long finished = all.stream().filter(s -> "finished".equals(s.getStatus())).count();
        int totalQuestion = all.stream().mapToInt(s -> s.getQuestionDone() == null ? 0 : s.getQuestionDone()).sum();
        int totalScore = all.stream().mapToInt(s -> s.getTotalScore() == null ? 0 : s.getTotalScore()).sum();
        int correct = all.stream().mapToInt(s -> s.getCorrectCount() == null ? 0 : s.getCorrectCount()).sum();
        double avgScore = totalQuestion == 0 ? 0.0 : (double) totalScore / totalQuestion;
        double accuracy = totalQuestion == 0 ? 0.0 : (double) correct / totalQuestion;

        // 薄弱 KB：按 KB 维度统计正确率，最低的 5 个
        List<CoachAnswer> answers = answerMapper.selectList(new LambdaQueryWrapper<CoachAnswer>()
                .eq(CoachAnswer::getUserId, userId));
        Map<Long, int[]> perKb = new HashMap<>();   // {kbId, [count, scoreSum]}
        for (CoachAnswer a : answers) {
            CoachQuestion q = questionMapper.selectById(a.getQuestionId());
            if (q == null || q.getSourceKbId() == null) continue;
            int[] agg = perKb.computeIfAbsent(q.getSourceKbId(), k -> new int[2]);
            agg[0]++;
            agg[1] += a.getScore() == null ? 0 : a.getScore();
        }
        List<Map<String, Object>> weak = perKb.entrySet().stream()
                .map(e -> {
                    int cnt = e.getValue()[0];
                    int sum = e.getValue()[1];
                    double avg = cnt == 0 ? 0 : (double) sum / cnt;
                    KbKnowledgeBase kb = kbMapper.selectById(e.getKey());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kbId", e.getKey());
                    m.put("kbName", kb == null ? "(已删除)" : kb.getName());
                    m.put("answered", cnt);
                    m.put("avgScore", Math.round(avg));
                    return m;
                })
                .sorted(Comparator.comparingDouble(m -> ((Number) m.get("avgScore")).doubleValue()))
                .limit(5)
                .collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionCount", sessionCount);
        out.put("finishedSessionCount", finished);
        out.put("answeredQuestionCount", totalQuestion);
        out.put("correctCount", correct);
        out.put("avgScore", Math.round(avgScore));
        out.put("accuracy", Math.round(accuracy * 1000) / 1000.0);
        out.put("weakKbs", weak);
        return out;
    }

    /** 主管/管理员：团队近 N 天用量（按用户分组） */
    public List<Map<String, Object>> teamStats(int recentDays) {
        LocalDate since = LocalDate.now().minusDays(Math.max(1, recentDays));
        LocalDateTime sinceDt = since.atStartOfDay();
        List<CoachSession> all = sessionMapper.selectList(new LambdaQueryWrapper<CoachSession>()
                .ge(CoachSession::getStartAt, sinceDt)
                .orderByDesc(CoachSession::getStartAt));

        Map<Long, int[]> perUser = new HashMap<>();    // {userId, [sessions, questionDone, scoreSum, correct]}
        for (CoachSession s : all) {
            int[] agg = perUser.computeIfAbsent(s.getUserId(), k -> new int[4]);
            agg[0]++;
            agg[1] += s.getQuestionDone() == null ? 0 : s.getQuestionDone();
            agg[2] += s.getTotalScore() == null ? 0 : s.getTotalScore();
            agg[3] += s.getCorrectCount() == null ? 0 : s.getCorrectCount();
        }
        return perUser.entrySet().stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", e.getKey());
                    m.put("sessions", e.getValue()[0]);
                    m.put("questionDone", e.getValue()[1]);
                    int done = e.getValue()[1];
                    m.put("avgScore", done == 0 ? 0 : Math.round((double) e.getValue()[2] / done));
                    m.put("accuracy", done == 0 ? 0.0
                            : Math.round((double) e.getValue()[3] / done * 1000) / 1000.0);
                    return m;
                })
                .sorted(Comparator.comparingInt(m -> -((Number) m.get("questionDone")).intValue()))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    private List<Long> parseKbIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSON.parseArray(json);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) ids.add(arr.getLong(i));
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildKbScopeLabel(List<Long> kbIds) {
        List<KbKnowledgeBase> kbs = kbMapper.selectBatchIds(kbIds);
        String join = kbs.stream().map(KbKnowledgeBase::getName)
                .filter(Objects::nonNull).limit(3).collect(Collectors.joining(" / "));
        if (kbs.size() > 3) join += " 等 " + kbs.size() + " 个";
        return join;
    }

    private String normalizeDifficulty(String d) {
        if (d == null) return "medium";
        String v = d.trim().toLowerCase(Locale.ROOT);
        return List.of("easy", "medium", "hard").contains(v) ? v : "medium";
    }

    private String normalizeTrueFalse(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return "";
        if (x.equals("对") || x.equals("正确") || x.equals("true") || x.equals("t") || x.equals("yes") || x.equals("y") || x.equals("✓"))
            return "对";
        if (x.equals("错") || x.equals("错误") || x.equals("false") || x.equals("f") || x.equals("no") || x.equals("n") || x.equals("✗"))
            return "错";
        return "";
    }

    private String normalizeChoice(String s) {
        if (s == null) return "";
        String x = s.trim().toUpperCase(Locale.ROOT);
        // 取第一个 A-D 字符
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if (c >= 'A' && c <= 'D') return String.valueOf(c);
        }
        return "";
    }

    private String stripCodeFence(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("```")) {
            int s = t.indexOf('{');
            int e = t.lastIndexOf('}');
            if (s >= 0 && e > s) return t.substring(s, e + 1);
        }
        return t;
    }

    private String nonBlank(String v, String errMsg) {
        if (v == null || v.isBlank()) throw new IllegalStateException("出题失败：" + errMsg);
        return v.trim();
    }

    private String getActiveModelName() {
        try {
            String m = aiConfigHolder.getString("llm.model");
            return m == null || m.isBlank() ? "qwen-plus" : m;
        } catch (Exception e) {
            return "qwen-plus";
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() * 2 / 3);
    }

    // ─────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────
    private static class GeneratedQuestion {
        int sourceIndex = -1;          // chunks 列表索引 · 用于 1:1 绑定
        String sourceQuote;            // 原文片段证据 · 后处理校验必须命中
        String question;
        String questionType;
        List<String> options;
        String expectedAnswer;
        String explanation;
    }

    private static class Judgement {
        final int score;
        final String judgment;
        final String feedback;
        Judgement(int score, String judgment, String feedback) {
            this.score = score;
            this.judgment = judgment;
            this.feedback = feedback;
        }
    }
}
