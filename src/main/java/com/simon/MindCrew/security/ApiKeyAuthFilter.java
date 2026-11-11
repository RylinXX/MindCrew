package com.simon.MindCrew.security;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.ApiKey;
import com.simon.MindCrew.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 对外 API Key 鉴权 Filter · 任务 11.2
 *
 * 仅拦截 /api/v3/** 路径（对外接口，跟内部 /api/v1, /api/v2 完全隔离）。
 * 校验通过后：
 *   - 把 ApiKey 对象塞进 ApiKeyContext (ThreadLocal)
 *   - 业务代码通过 ApiKeyContext.current() 拿当前 key 做 KB 权限判定
 *   - 请求结束自动清理 ThreadLocal（防止线程复用泄露）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    @Autowired
    private ApiKeyService apiKeyService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只对 /api/v3/** 生效
        return !request.getRequestURI().startsWith("/api/v3/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String raw = extractKey(request);
        try {
            ApiKey key = apiKeyService.authenticate(raw);
            ApiKeyContext.set(key);
            chain.doFilter(request, response);
        } catch (ApiKeyService.ApiAuthException e) {
            writeError(response, e.getHttpStatus(), e.getMessage());
        } finally {
            ApiKeyContext.clear();
        }
    }

    /** Authorization: Bearer mk_xxxxxxxx */
    private String extractKey(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7).trim();
        // 兼容查询参数（部分 SDK 不方便加 header，比如简单 curl 测试）
        return request.getParameter("api_key");
    }

    private void writeError(HttpServletResponse response, int httpStatus, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.error(httpStatus, message);
        response.getWriter().write(JSON.toJSONString(body));
    }
}
