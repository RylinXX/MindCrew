package com.simon.MindCrew.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解 · 任务 12.1
 *
 * 用法：
 *   @Audited(action = "kb.upload", label = "上传知识库", targetType = "kb", targetIdParam = "kbId")
 *   public Long uploadDocument(... Long userId) { ... }
 *
 * AOP 自动拦截：
 *   - 方法成功执行 → status=success，detail_json 含入参与返回值
 *   - 抛异常 → status=failure，error_msg 记录异常 message
 *   - 异步落库不阻塞业务
 *
 * 务实约束：
 *   - 仅记关键变更操作，**不**记纯读类查询（不然日志爆炸）
 *   - detail_json 自动脱敏（按 PiiConfig.applyOnAudit）
 *   - 超长内容截前 1000 字符
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 动作 code · 命名规则 module.action · 如 kb.upload / user.login / acl.grant */
    String action();

    /** 中文标签 · 用于日志列表展示 */
    String label() default "";

    /** 目标类型 · kb / user / api_key / golden_pair / persona / ... */
    String targetType() default "";

    /**
     * 目标 ID 来源 · 可选值：
     *   - 空 → 不记 target_id
     *   - "$argN" → 取第 N 个方法参数
     *   - "$return" → 取返回值
     *   - "$argN.field" → 取参数对象的字段
     */
    String targetIdParam() default "";

    /** 是否在 detail_json 里记录方法参数（默认 true） */
    boolean recordArgs() default true;
}
