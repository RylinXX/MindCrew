package com.simon.MindCrew.common.audit;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.entity.AuditLog;
import com.simon.MindCrew.service.AuditLogService;
import com.simon.MindCrew.service.PiiMaskService;
import com.simon.MindCrew.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * @Audited 切面 · 任务 12.1
 *
 * 拦截所有标注了 @Audited 的方法：
 *   - 方法执行前：记录入参 + IP + UA + userId
 *   - 方法执行成功：记 success，含返回值
 *   - 方法抛异常：记 failure + errorMsg，然后**重新抛出**（不吞异常）
 *
 * detail_json 大小限制 · 超长截断防爆。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogService auditLogService;
    private final PiiMaskService piiMaskService;
    private final UserService userService;

    private static final int MAX_DETAIL_LEN = 2000;

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        long t0 = System.currentTimeMillis();
        AuditLog log = new AuditLog();
        log.setAction(audited.action());
        log.setActionLabel(audited.label().isBlank() ? audited.action() : audited.label());
        log.setTargetType(audited.targetType().isBlank() ? null : audited.targetType());
        log.setStatus("success");
        log.setLatencyMs(0);

        // 当前用户
        try {
            Long uid = userService.getCurrentUserId();
            if (uid != null) {
                log.setUserId(uid);
                // username 单独取（避免抛异常）
                try {
                    var u = userService.getCurrentUser();
                    if (u != null) log.setUsername(u.getUsername());
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 请求上下文（IP / UA）
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                log.setIp(req.getRemoteAddr());
                log.setUserAgent(req.getHeader("User-Agent"));
            }
        } catch (Exception ignored) {}

        // 入参快照
        Object[] args = pjp.getArgs();
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        String[] paramNames = sig.getParameterNames();

        Map<String, Object> detail = new HashMap<>();
        if (audited.recordArgs() && args != null) {
            for (int i = 0; i < args.length; i++) {
                String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : ("arg" + i);
                detail.put(name, safeStringify(args[i]));
            }
        }

        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            // 简化：只记 result 类型 + toString（避免大对象爆库）
            if (result != null) detail.put("result", safeStringify(result));
            return result;
        } catch (Throwable t) {
            error = t;
            log.setStatus("failure");
            log.setErrorMsg(truncate(t.getMessage(), 500));
            throw t;
        } finally {
            log.setLatencyMs((int) (System.currentTimeMillis() - t0));

            // 解析 target_id
            String tid = resolveTargetId(audited.targetIdParam(), args, paramNames, result);
            if (tid != null && !tid.isBlank()) log.setTargetId(tid);

            // detail_json 脱敏 + 截长度
            try {
                String json = JSON.toJSONString(detail);
                json = piiMaskService.maskForAudit(json);
                if (json.length() > MAX_DETAIL_LEN) {
                    json = json.substring(0, MAX_DETAIL_LEN) + "...[truncated]";
                }
                log.setDetailJson(json);
            } catch (Exception e) {
                log.setDetailJson("{\"_serialize_error\":\"" + e.getMessage() + "\"}");
            }

            // 异步入库
            try {
                auditLogService.recordAsync(log);
            } catch (Exception logEx) {
                AuditAspect.log.warn("[Audit] aspect 入库失败: {}", logEx.getMessage());
            }
        }
    }

    /**
     * 解析 targetIdParam 的取值：
     *   ""        → null
     *   "$argN"   → 第 N 个参数
     *   "$return" → 返回值
     *   "$argN.field" → 参数对象的某字段（用反射）
     *   其他      → 直接当字面值
     */
    private String resolveTargetId(String expr, Object[] args, String[] paramNames, Object result) {
        if (expr == null || expr.isBlank()) return null;
        try {
            if ("$return".equals(expr)) return result == null ? null : String.valueOf(result);
            if (expr.startsWith("$arg")) {
                String body = expr.substring(4);   // 去 $arg
                int dot = body.indexOf('.');
                String idxStr = dot >= 0 ? body.substring(0, dot) : body;
                int idx = Integer.parseInt(idxStr);
                if (args == null || idx >= args.length) return null;
                Object v = args[idx];
                if (v == null) return null;
                if (dot >= 0) {
                    String field = body.substring(dot + 1);
                    try {
                        Method getter = v.getClass().getMethod("get" + capitalize(field));
                        Object fv = getter.invoke(v);
                        return fv == null ? null : String.valueOf(fv);
                    } catch (Exception e) {
                        return null;
                    }
                }
                return String.valueOf(v);
            }
            return expr;     // 字面值
        } catch (Exception e) {
            return null;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String safeStringify(Object o) {
        if (o == null) return null;
        // 复杂对象 → JSON，简单类型 → toString
        if (o instanceof Number || o instanceof Boolean || o instanceof String) {
            return o.toString();
        }
        try {
            String s = JSON.toJSONString(o);
            return truncate(s, 500);
        } catch (Exception e) {
            return o.getClass().getSimpleName();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
