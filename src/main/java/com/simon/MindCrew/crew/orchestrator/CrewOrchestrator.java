package com.simon.MindCrew.crew.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.crew.agents.CriticAgent;
import com.simon.MindCrew.crew.agents.PlannerAgent;
import com.simon.MindCrew.crew.agents.ResearcherAgent;
import com.simon.MindCrew.crew.agents.WriterAgent;
import com.simon.MindCrew.crew.dto.CrewEvent;
import com.simon.MindCrew.crew.dto.Finding;
import com.simon.MindCrew.crew.dto.PlanItem;
import com.simon.MindCrew.crew.dto.ReviewResult;
import com.simon.MindCrew.crew.entity.AgentStep;
import com.simon.MindCrew.crew.entity.AgentTask;
import com.simon.MindCrew.crew.enums.AgentRole;
import com.simon.MindCrew.crew.enums.StepStatus;
import com.simon.MindCrew.crew.enums.TaskStatus;
import com.simon.MindCrew.crew.mapper.AgentStepMapper;
import com.simon.MindCrew.crew.mapper.AgentTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-Agent Crew 状态机协调器。
 *
 * 执行序列：
 *   PENDING → PLANNING → RESEARCHING (并行 N 路) → WRITING → REVIEWING
 *           → (评审未通过) → REVISING → REVIEWING(2nd)
 *           → COMPLETED
 *
 * 每个状态切换都：
 *   1. 持久化 AgentTask.status
 *   2. 创建对应 AgentStep
 *   3. 通过 SSE 推事件到前端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrewOrchestrator {

    private final PlannerAgent      plannerAgent;
    private final ResearcherAgent   researcherAgent;
    private final WriterAgent       writerAgent;
    private final CriticAgent       criticAgent;

    private final AgentTaskMapper   taskMapper;
    private final AgentStepMapper   stepMapper;

    /** 并行 Researcher 的线程池 */
    private final ExecutorService researchPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "crew-researcher-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    /** 最大重写轮次 */
    private static final int MAX_REVISIONS = 1;

    /**
     * 创建任务但不立即执行（供 Controller 先返回 taskId）。
     */
    public AgentTask createTask(Long userId, Long conversationId, String query, List<Long> kbIds) {
        AgentTask task = new AgentTask();
        task.setUserId(userId);
        task.setConversationId(conversationId);
        task.setQuery(query);
        task.setKbIds(kbIds == null || kbIds.isEmpty() ? null : JSON.toJSONString(kbIds));
        task.setStatus(TaskStatus.PENDING.name());
        task.setRevisionCount(0);
        task.setTotalSteps(0);
        task.setTotalTokens(0);
        task.setElapsedMs(0L);
        taskMapper.insert(task);
        return task;
    }

    /**
     * 异步运行任务，事件通过 SSE Emitter 流式推送。
     */
    @Async
    public void runAsync(Long taskId, SseEmitter emitter) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            emit(emitter, CrewEvent.of("task.failed").put("error", "Task not found"));
            closeEmitter(emitter);
            return;
        }

        long t0 = System.currentTimeMillis();
        task.setStartTime(LocalDateTime.now());
        AtomicInteger stepCounter = new AtomicInteger(0);
        List<Long> kbIds = parseKbIds(task.getKbIds());

        try {
            emit(emitter, CrewEvent.of("task.start")
                    .put("taskId", taskId)
                    .put("query", task.getQuery())
                    .progress(0.0));

            // ───── Phase 1: Planning ─────
            List<PlanItem> plan = runPlanner(task, emitter, stepCounter);
            if (plan.isEmpty()) {
                throw new IllegalStateException("Planner 未产出可执行子任务");
            }
            task.setPlanJson(JSON.toJSONString(plan));

            // ───── Phase 2: Researching (并行) ─────
            List<Finding> findings = runResearchersParallel(task, plan, kbIds, emitter, stepCounter);

            // ───── Phase 3: Writing ─────
            String report = runWriter(task, findings, null, emitter, stepCounter);

            // ───── Phase 4: Reviewing ─────
            ReviewResult review = runCritic(task, findings, report, emitter, stepCounter);

            // ───── Phase 5: Revising (if needed) ─────
            if (Boolean.FALSE.equals(review.getPassed()) && task.getRevisionCount() < MAX_REVISIONS) {
                emit(emitter, CrewEvent.of("revision.start")
                        .put("reason", review.getSuggestion())
                        .progress(0.85));

                task.setStatus(TaskStatus.REVISING.name());
                task.setRevisionCount(task.getRevisionCount() + 1);
                taskMapper.updateById(task);

                report = runWriter(task, findings, review.getSuggestion(), emitter, stepCounter);
                review = runCritic(task, findings, report, emitter, stepCounter);
            }

            // ───── Phase 6: Done ─────
            task.setStatus(TaskStatus.COMPLETED.name());
            task.setCurrentRole(null);
            task.setFinalReport(report);
            task.setReviewScore(review.getScore() == null ? null : BigDecimal.valueOf(review.getScore()));
            task.setEndTime(LocalDateTime.now());
            task.setElapsedMs(System.currentTimeMillis() - t0);
            task.setTotalSteps(stepCounter.get());
            taskMapper.updateById(task);

            emit(emitter, CrewEvent.of("task.done")
                    .put("report", report)
                    .put("score", review.getScore())
                    .put("totalSteps", stepCounter.get())
                    .put("elapsedMs", task.getElapsedMs())
                    .progress(1.0));

        } catch (Exception e) {
            log.error("[Orchestrator] task {} failed", taskId, e);
            task.setStatus(TaskStatus.FAILED.name());
            task.setErrorMsg(e.getMessage());
            task.setEndTime(LocalDateTime.now());
            task.setElapsedMs(System.currentTimeMillis() - t0);
            taskMapper.updateById(task);

            emit(emitter, CrewEvent.of("task.failed").put("error", e.getMessage()));
        } finally {
            closeEmitter(emitter);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Phase 实现
    // ─────────────────────────────────────────────────────────

    private List<PlanItem> runPlanner(AgentTask task, SseEmitter emitter, AtomicInteger counter) {
        task.setStatus(TaskStatus.PLANNING.name());
        task.setCurrentRole(AgentRole.PLANNER.getCode());
        taskMapper.updateById(task);

        int idx = counter.incrementAndGet();
        AgentStep step = startStep(task.getId(), idx, AgentRole.PLANNER, "任务分解", task.getQuery(), null);
        emit(emitter, CrewEvent.of("agent.start").role(AgentRole.PLANNER.getCode()).step(idx).progress(0.05));

        long t = System.currentTimeMillis();
        List<PlanItem> plan;
        try {
            plan = plannerAgent.plan(task.getQuery());
            step.setOutput(JSON.toJSONString(plan));
            finishStep(step, StepStatus.DONE, System.currentTimeMillis() - t);
        } catch (Exception e) {
            failStep(step, e.getMessage(), System.currentTimeMillis() - t);
            throw e;
        }

        emit(emitter, CrewEvent.of("planner.plan")
                .role(AgentRole.PLANNER.getCode())
                .step(idx)
                .put("plan", plan)
                .progress(0.15));
        emit(emitter, CrewEvent.of("agent.done").role(AgentRole.PLANNER.getCode()).step(idx));
        return plan;
    }

    private List<Finding> runResearchersParallel(AgentTask task, List<PlanItem> plan, List<Long> kbIds,
                                                  SseEmitter emitter, AtomicInteger counter) {
        task.setStatus(TaskStatus.RESEARCHING.name());
        task.setCurrentRole(AgentRole.RESEARCHER.getCode());
        taskMapper.updateById(task);

        emit(emitter, CrewEvent.of("agent.start")
                .role(AgentRole.RESEARCHER.getCode())
                .put("totalSubtasks", plan.size())
                .progress(0.2));

        // 为每个 PlanItem 提交一个并行任务
        List<CompletableFuture<Finding>> futures = new ArrayList<>();
        for (PlanItem item : plan) {
            final int idx = counter.incrementAndGet();
            AgentStep step = startStep(task.getId(), idx, AgentRole.RESEARCHER,
                    "调研：" + item.getTitle(), item.getQuery(), item.getQuery());

            emit(emitter, CrewEvent.of("researcher.start")
                    .role(AgentRole.RESEARCHER.getCode())
                    .step(idx)
                    .put("planIndex", item.getIndex())
                    .put("title", item.getTitle()));

            CompletableFuture<Finding> f = CompletableFuture.supplyAsync(() -> {
                long t = System.currentTimeMillis();
                try {
                    Finding finding = researcherAgent.research(item, kbIds);
                    step.setOutput(JSON.toJSONString(finding));
                    finishStep(step, StepStatus.DONE, System.currentTimeMillis() - t);
                    emit(emitter, CrewEvent.of("researcher.finding")
                            .role(AgentRole.RESEARCHER.getCode())
                            .step(idx)
                            .put("finding", finding));
                    return finding;
                } catch (Exception e) {
                    log.warn("[Orchestrator] researcher subtask {} failed: {}", item.getIndex(), e.getMessage());
                    failStep(step, e.getMessage(), System.currentTimeMillis() - t);
                    // 失败兜底：返回空 Finding 而不中断整个任务
                    return new Finding(item.getIndex(), item.getTitle(), item.getSection(),
                            "该子主题调研失败：" + e.getMessage(), List.of());
                }
            }, researchPool);
            futures.add(f);
        }

        // 等所有 Researcher 完成
        List<Finding> findings = new ArrayList<>();
        for (CompletableFuture<Finding> f : futures) {
            try { findings.add(f.get(120, TimeUnit.SECONDS)); }
            catch (Exception e) {
                log.warn("[Orchestrator] research future failed: {}", e.getMessage());
            }
        }
        findings.sort(Comparator.comparing(Finding::getPlanIndex));

        emit(emitter, CrewEvent.of("agent.done")
                .role(AgentRole.RESEARCHER.getCode())
                .put("findingCount", findings.size())
                .progress(0.55));
        return findings;
    }

    private String runWriter(AgentTask task, List<Finding> findings, String critique,
                              SseEmitter emitter, AtomicInteger counter) {
        boolean isRevision = critique != null && !critique.isBlank();
        task.setStatus(TaskStatus.WRITING.name());
        task.setCurrentRole(AgentRole.WRITER.getCode());
        taskMapper.updateById(task);

        int idx = counter.incrementAndGet();
        AgentStep step = startStep(task.getId(), idx, AgentRole.WRITER,
                isRevision ? "重写报告" : "撰写报告",
                isRevision ? "评审反馈：" + critique : null,
                null);

        emit(emitter, CrewEvent.of("agent.start")
                .role(AgentRole.WRITER.getCode())
                .step(idx)
                .put("revision", isRevision)
                .progress(isRevision ? 0.88 : 0.6));

        long t = System.currentTimeMillis();
        String report;
        try {
            report = writerAgent.write(task.getQuery(), findings, critique, token -> {
                // 流式 token 推送给前端
                emit(emitter, CrewEvent.of("writer.token")
                        .role(AgentRole.WRITER.getCode())
                        .step(idx)
                        .put("delta", token));
            });
            step.setOutput(report);
            finishStep(step, StepStatus.DONE, System.currentTimeMillis() - t);
        } catch (Exception e) {
            // WriterAgent 内部已有双层 fallback，此处兜底确保不中断管线
            log.error("[Orchestrator] writer failed, falling back to stub report: {}", e.getMessage());
            report = "# 报告生成失败\n\n" + e.getMessage();
            failStep(step, e.getMessage(), System.currentTimeMillis() - t);
            emit(emitter, CrewEvent.of("agent.failed")
                    .role(AgentRole.WRITER.getCode())
                    .step(idx)
                    .put("error", e.getMessage()));
        }

        emit(emitter, CrewEvent.of("writer.done")
                .role(AgentRole.WRITER.getCode())
                .step(idx)
                .put("report", report)
                .progress(isRevision ? 0.95 : 0.75));
        return report;
    }

    private ReviewResult runCritic(AgentTask task, List<Finding> findings, String report,
                                    SseEmitter emitter, AtomicInteger counter) {
        task.setStatus(TaskStatus.REVIEWING.name());
        task.setCurrentRole(AgentRole.CRITIC.getCode());
        taskMapper.updateById(task);

        int idx = counter.incrementAndGet();
        AgentStep step = startStep(task.getId(), idx, AgentRole.CRITIC, "质量评审", null, null);
        emit(emitter, CrewEvent.of("agent.start")
                .role(AgentRole.CRITIC.getCode())
                .step(idx)
                .progress(0.8));

        long t = System.currentTimeMillis();
        ReviewResult review;
        try {
            review = criticAgent.review(task.getQuery(), findings, report);
            step.setOutput(JSON.toJSONString(review));
            finishStep(step, StepStatus.DONE, System.currentTimeMillis() - t);
        } catch (Exception e) {
            // CriticAgent 内部已有 fallback default-pass，此处兜底确保不中断管线
            log.error("[Orchestrator] critic failed, falling back to default-pass: {}", e.getMessage());
            review = new ReviewResult(0.8, true, 0.8, 0.8, 0.8,
                    List.of("评审异常：" + e.getMessage()),
                    "评审失败，按默认通过处理。");
            failStep(step, e.getMessage(), System.currentTimeMillis() - t);
            emit(emitter, CrewEvent.of("agent.failed")
                    .role(AgentRole.CRITIC.getCode())
                    .step(idx)
                    .put("error", e.getMessage()));
        }

        emit(emitter, CrewEvent.of("critic.review")
                .role(AgentRole.CRITIC.getCode())
                .step(idx)
                .put("review", review)
                .progress(0.9));
        return review;
    }

    // ─────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────

    private AgentStep startStep(Long taskId, Integer index, AgentRole role,
                                 String name, String input, String subtask) {
        AgentStep step = new AgentStep();
        step.setTaskId(taskId);
        step.setStepIndex(index);
        step.setAgentRole(role.getCode());
        step.setStepName(name);
        step.setInput(input);
        step.setSubtask(subtask);
        step.setStatus(StepStatus.RUNNING.name());
        step.setElapsedMs(0L);
        step.setTokens(0);
        stepMapper.insert(step);
        return step;
    }

    private void finishStep(AgentStep step, StepStatus status, long elapsed) {
        step.setStatus(status.name());
        step.setElapsedMs(elapsed);
        stepMapper.updateById(step);
    }

    private void failStep(AgentStep step, String err, long elapsed) {
        step.setStatus(StepStatus.FAILED.name());
        step.setErrorMsg(err);
        step.setElapsedMs(elapsed);
        stepMapper.updateById(step);
    }

    private List<Long> parseKbIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.parseArray(json, Long.class);
        } catch (Exception e) {
            log.warn("[Orchestrator] failed to parse kbIds: {}", json);
            return List.of();
        }
    }

    private void emit(SseEmitter emitter, CrewEvent event) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType())
                    .data(JSON.toJSONString(event)));
        } catch (IOException e) {
            log.debug("[Orchestrator] sse send failed (client may have disconnected): {}", e.getMessage());
        }
    }

    private void closeEmitter(SseEmitter emitter) {
        if (emitter == null) return;
        try { emitter.complete(); }
        catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────
    // Time-Travel · Fork & Replay
    // ─────────────────────────────────────────────────────────

    /**
     * 创建一个 Fork 任务：复制原任务到指定步骤为止，并将该步骤的输出替换为用户编辑后的版本。
     *
     * 4 种 fork 语义（按编辑的角色）：
     *  - PLANNER   → 用编辑后的 plan，重新跑 Researcher × N + Writer + Critic
     *  - RESEARCHER → 复制其他并行 Researcher 的发现，仅替换被编辑的那个，重新跑 Writer + Critic
     *  - WRITER    → 用编辑后的报告，仅重新跑 Critic
     *  - CRITIC    → 用编辑后的评审结果直接收尾；如 passed=false 触发重写循环
     */
    public AgentTask forkTask(Long originalId, Integer fromStepIndex,
                               String editedOutput, String editSummary) {
        AgentTask original = taskMapper.selectById(originalId);
        if (original == null) {
            throw new IllegalArgumentException("原任务不存在: " + originalId);
        }

        List<AgentStep> originalSteps = stepMapper.selectList(
                new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getTaskId, originalId)
                        .orderByAsc(AgentStep::getStepIndex)
        );

        AgentStep targetStep = originalSteps.stream()
                .filter(s -> Objects.equals(s.getStepIndex(), fromStepIndex))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("步骤 #" + fromStepIndex + " 不存在"));

        boolean isResearcherFork = AgentRole.RESEARCHER.getCode().equals(targetStep.getAgentRole());

        AgentTask fork = new AgentTask();
        fork.setUserId(original.getUserId());
        fork.setConversationId(original.getConversationId());
        fork.setQuery(original.getQuery());
        fork.setKbIds(original.getKbIds());
        fork.setStatus(TaskStatus.PENDING.name());
        fork.setParentTaskId(originalId);
        fork.setForkedFromStep(fromStepIndex);
        fork.setForkEditSummary(editSummary);
        fork.setRevisionCount(0);
        fork.setTotalSteps(0);
        fork.setTotalTokens(0);
        fork.setElapsedMs(0L);
        taskMapper.insert(fork);

        // 复制策略：所有 stepIndex<=fromStepIndex 的步骤；
        // 若编辑的是 Researcher，并行的其他 Researcher 也一并复制（它们没有依赖关系）
        int newIndex = 0;
        for (AgentStep s : originalSteps) {
            boolean shouldCopy;
            if (s.getStepIndex() < fromStepIndex) {
                shouldCopy = true;
            } else if (Objects.equals(s.getStepIndex(), fromStepIndex)) {
                shouldCopy = true;
            } else if (isResearcherFork && AgentRole.RESEARCHER.getCode().equals(s.getAgentRole())) {
                shouldCopy = true;
            } else {
                shouldCopy = false;
            }
            if (!shouldCopy) continue;

            newIndex++;
            AgentStep copy = new AgentStep();
            copy.setTaskId(fork.getId());
            copy.setStepIndex(newIndex);
            copy.setAgentRole(s.getAgentRole());
            copy.setStepName(s.getStepName());
            copy.setSubtask(s.getSubtask());
            copy.setInput(s.getInput());
            copy.setStatus(StepStatus.DONE.name());
            copy.setElapsedMs(s.getElapsedMs() == null ? 0L : s.getElapsedMs());
            copy.setTokens(s.getTokens() == null ? 0 : s.getTokens());

            if (Objects.equals(s.getStepIndex(), fromStepIndex) && editedOutput != null) {
                copy.setOutput(editedOutput);
                copy.setStepName(s.getStepName() + "（用户编辑）");
            } else {
                copy.setOutput(s.getOutput());
            }
            stepMapper.insert(copy);
        }

        // 若编辑的是 Planner，把新 plan 同步到 task.planJson 供前端展示
        if (AgentRole.PLANNER.getCode().equals(targetStep.getAgentRole()) && editedOutput != null) {
            fork.setPlanJson(editedOutput);
            taskMapper.updateById(fork);
        }

        log.info("[Orchestrator] forked task {} from original {} at step {} ({} role)",
                fork.getId(), originalId, fromStepIndex, targetStep.getAgentRole());
        return fork;
    }

    /**
     * 异步运行 Fork 任务，事件流式推送。
     */
    @Async
    public void runForkAsync(Long forkId, SseEmitter emitter) {
        AgentTask fork = taskMapper.selectById(forkId);
        if (fork == null) {
            emit(emitter, CrewEvent.of("task.failed").put("error", "Fork 任务不存在"));
            closeEmitter(emitter);
            return;
        }

        long t0 = System.currentTimeMillis();
        fork.setStartTime(LocalDateTime.now());
        List<Long> kbIds = parseKbIds(fork.getKbIds());

        List<AgentStep> existingSteps = stepMapper.selectList(
                new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getTaskId, forkId)
                        .orderByAsc(AgentStep::getStepIndex)
        );

        AtomicInteger stepCounter = new AtomicInteger(existingSteps.size());

        try {
            emit(emitter, CrewEvent.of("task.start")
                    .put("taskId", forkId)
                    .put("query", fork.getQuery())
                    .put("isFork", true)
                    .put("parentTaskId", fork.getParentTaskId())
                    .put("forkedFromStep", fork.getForkedFromStep())
                    .progress(0.0));

            // 把已复制的步骤回放给前端，前端按 fork.replay-step 渲染历史
            for (AgentStep s : existingSteps) {
                emit(emitter, CrewEvent.of("fork.replay-step")
                        .role(s.getAgentRole())
                        .step(s.getStepIndex())
                        .put("step", s));
            }

            Integer fromStep = fork.getForkedFromStep();
            AgentStep editedStep = existingSteps.stream()
                    .filter(s -> Objects.equals(s.getStepIndex(), fromStep))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Fork 任务缺少编辑步骤"));

            String editedRole = editedStep.getAgentRole();

            if (AgentRole.PLANNER.getCode().equals(editedRole)) {
                runForkFromPlanner(fork, editedStep, kbIds, stepCounter, emitter, t0);
            } else if (AgentRole.RESEARCHER.getCode().equals(editedRole)) {
                runForkFromResearcher(fork, existingSteps, stepCounter, emitter, t0);
            } else if (AgentRole.WRITER.getCode().equals(editedRole)) {
                runForkFromWriter(fork, existingSteps, editedStep, stepCounter, emitter, t0);
            } else if (AgentRole.CRITIC.getCode().equals(editedRole)) {
                runForkFromCritic(fork, existingSteps, editedStep, kbIds, stepCounter, emitter, t0);
            } else {
                throw new IllegalStateException("未知编辑角色: " + editedRole);
            }

        } catch (Exception e) {
            log.error("[Orchestrator] fork {} failed", forkId, e);
            fork.setStatus(TaskStatus.FAILED.name());
            fork.setErrorMsg(e.getMessage());
            fork.setEndTime(LocalDateTime.now());
            fork.setElapsedMs(System.currentTimeMillis() - t0);
            taskMapper.updateById(fork);
            emit(emitter, CrewEvent.of("task.failed").put("error", e.getMessage()));
        } finally {
            closeEmitter(emitter);
        }
    }

    private void runForkFromPlanner(AgentTask fork, AgentStep editedStep, List<Long> kbIds,
                                     AtomicInteger counter, SseEmitter emitter, long t0) {
        List<PlanItem> plan = JSON.parseArray(editedStep.getOutput(), PlanItem.class);
        if (plan == null || plan.isEmpty()) {
            throw new IllegalArgumentException("编辑后的 Plan 解析失败或为空");
        }
        List<Finding> findings = runResearchersParallel(fork, plan, kbIds, emitter, counter);
        String report = runWriter(fork, findings, null, emitter, counter);
        ReviewResult review = runCritic(fork, findings, report, emitter, counter);
        finalizeFork(fork, report, review, t0, counter, emitter);
    }

    private void runForkFromResearcher(AgentTask fork, List<AgentStep> existing,
                                        AtomicInteger counter, SseEmitter emitter, long t0) {
        List<Finding> findings = collectFindings(existing);
        if (findings.isEmpty()) {
            throw new IllegalStateException("无可用 Researcher 发现，无法继续");
        }
        String report = runWriter(fork, findings, null, emitter, counter);
        ReviewResult review = runCritic(fork, findings, report, emitter, counter);
        finalizeFork(fork, report, review, t0, counter, emitter);
    }

    private void runForkFromWriter(AgentTask fork, List<AgentStep> existing, AgentStep editedStep,
                                    AtomicInteger counter, SseEmitter emitter, long t0) {
        String editedReport = editedStep.getOutput();
        List<Finding> findings = collectFindings(existing);
        ReviewResult review = runCritic(fork, findings, editedReport, emitter, counter);
        finalizeFork(fork, editedReport, review, t0, counter, emitter);
    }

    private void runForkFromCritic(AgentTask fork, List<AgentStep> existing, AgentStep editedStep,
                                    List<Long> kbIds, AtomicInteger counter, SseEmitter emitter, long t0) {
        ReviewResult editedReview;
        try {
            editedReview = JSON.parseObject(editedStep.getOutput(), ReviewResult.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("编辑后的 Critic 评分 JSON 格式错误");
        }
        String lastReport = findLastWriterReport(existing);

        if (Boolean.FALSE.equals(editedReview.getPassed()) && fork.getRevisionCount() < MAX_REVISIONS) {
            emit(emitter, CrewEvent.of("revision.start")
                    .put("reason", editedReview.getSuggestion())
                    .progress(0.85));
            fork.setStatus(TaskStatus.REVISING.name());
            fork.setRevisionCount(fork.getRevisionCount() + 1);
            taskMapper.updateById(fork);

            List<Finding> findings = collectFindings(existing);
            String revised = runWriter(fork, findings, editedReview.getSuggestion(), emitter, counter);
            ReviewResult finalReview = runCritic(fork, findings, revised, emitter, counter);
            finalizeFork(fork, revised, finalReview, t0, counter, emitter);
        } else {
            finalizeFork(fork, lastReport, editedReview, t0, counter, emitter);
        }
    }

    private void finalizeFork(AgentTask fork, String report, ReviewResult review,
                               long t0, AtomicInteger counter, SseEmitter emitter) {
        fork.setStatus(TaskStatus.COMPLETED.name());
        fork.setCurrentRole(null);
        fork.setFinalReport(report);
        fork.setReviewScore(review.getScore() == null ? null : BigDecimal.valueOf(review.getScore()));
        fork.setEndTime(LocalDateTime.now());
        fork.setElapsedMs(System.currentTimeMillis() - t0);
        fork.setTotalSteps(counter.get());
        taskMapper.updateById(fork);

        emit(emitter, CrewEvent.of("task.done")
                .put("report", report)
                .put("score", review.getScore())
                .put("totalSteps", counter.get())
                .put("elapsedMs", fork.getElapsedMs())
                .progress(1.0));
    }

    /** 从复制过来的步骤中收集 Researcher 的 Finding 列表（含用户编辑的版本） */
    private List<Finding> collectFindings(List<AgentStep> steps) {
        List<Finding> findings = new ArrayList<>();
        for (AgentStep s : steps) {
            if (!AgentRole.RESEARCHER.getCode().equals(s.getAgentRole())) continue;
            if (s.getOutput() == null || s.getOutput().isBlank()) continue;
            try {
                Finding f = JSON.parseObject(s.getOutput(), Finding.class);
                if (f != null) findings.add(f);
            } catch (Exception e) {
                // 用户可能编辑成了纯文本，兜底封装成最小 Finding
                log.info("[Orchestrator] researcher output not valid JSON, wrapping as raw summary (step={})", s.getStepIndex());
                Finding f = new Finding();
                f.setPlanIndex(s.getStepIndex());
                f.setTitle(s.getStepName());
                f.setSection(s.getStepName());
                f.setSummary(s.getOutput());
                f.setSources(List.of());
                findings.add(f);
            }
        }
        findings.sort(Comparator.comparing(f -> f.getPlanIndex() == null ? 0 : f.getPlanIndex()));
        return findings;
    }

    /** 从复制过来的步骤中找最后一个 Writer 输出 */
    private String findLastWriterReport(List<AgentStep> steps) {
        String report = null;
        for (AgentStep s : steps) {
            if (AgentRole.WRITER.getCode().equals(s.getAgentRole()) && s.getOutput() != null) {
                report = s.getOutput();
            }
        }
        return report == null ? "（无可用报告）" : report;
    }
}
