package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.common.utils.JwtUtils;
import com.simon.MindCrew.entity.CsConfig;
import com.simon.MindCrew.entity.CsSsoApp;
import com.simon.MindCrew.entity.CsSsoUser;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.CsSsoAppMapper;
import com.simon.MindCrew.mapper.CsSsoUserMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 客服中心 · SSO 单点登录（契约 3.3）
 *
 * 签名：sign = hex(HMAC_SHA256(appSecret, appId + "\n" + externalUserId + "\n" + ts + "\n" + nonce))
 * 验签通过后写一次性 ticket（Redis 300s），前端 exchange 换 JWT。
 * ts 允许 ±300s；nonce 用 Redis 防重放（600s）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsSsoService {

    private static final long TS_TOLERANCE_SECONDS = 300L;
    private static final long NONCE_TTL_SECONDS = 600L;
    private static final String NONCE_PREFIX = "cs:sso:nonce:";
    private static final String DEFAULT_REDIRECT = "/cs";
    private static final String ERROR_INVALID_SIGN = "invalid_sign";

    private final CsSsoAppMapper appMapper;
    private final CsSsoUserMapper ssoUserMapper;
    private final SysUserMapper sysUserMapper;
    private final CsConfigService configService;
    private final CsTokenService tokenService;
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtils jwtUtils;
    private final AesCryptoUtils aesCryptoUtils;
    private final PasswordEncoder passwordEncoder;

    // ─────────────────────────────────────────────
    // 入口：302 目标地址
    // ─────────────────────────────────────────────

    /**
     * 计算 /api/cs/sso/entry 的 302 Location。
     * 任何校验失败都返回 redirect + "?cs_error=invalid_sign"（契约 3.3）。
     */
    @Transactional
    public String buildEntryRedirect(String appId, String externalUserId, String name, String ts,
                                     String nonce, String sign, String redirect) {
        String safeRedirect = sanitizeRedirect(redirect);
        CsConfig cfg = configService.getEntity();

        if (!Integer.valueOf(1).equals(cfg.getSsoEnabled())) {
            log.warn("[CS-SSO] sso_enabled=0，拒绝入口请求 appId={}", appId);
            return errorUrl(safeRedirect);
        }
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(externalUserId)
                || !StringUtils.hasText(ts) || !StringUtils.hasText(nonce) || !StringUtils.hasText(sign)) {
            log.warn("[CS-SSO] 缺少必填参数 appId={} userId={} ts={} nonce={}", appId, externalUserId, ts, nonce);
            return errorUrl(safeRedirect);
        }

        CsSsoApp app = findEnabledApp(appId.trim());
        if (app == null) {
            log.warn("[CS-SSO] app 不存在或未启用 appId={}", appId);
            return errorUrl(safeRedirect);
        }

        long tsSeconds;
        try {
            tsSeconds = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            log.warn("[CS-SSO] ts 非法: {}", ts);
            return errorUrl(safeRedirect);
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSeconds - tsSeconds) > TS_TOLERANCE_SECONDS) {
            log.warn("[CS-SSO] ts 超出 ±300s: ts={} now={}", tsSeconds, nowSeconds);
            return errorUrl(safeRedirect);
        }

        String appSecret = aesCryptoUtils.decrypt(app.getAppSecret());
        if (!StringUtils.hasText(appSecret)) {
            log.warn("[CS-SSO] appSecret 未配置 appId={}", appId);
            return errorUrl(safeRedirect);
        }

        String signData = appId.trim() + "\n" + externalUserId.trim() + "\n" + ts.trim() + "\n" + nonce.trim();
        String expected = CsTokenService.hmacHex(appSecret, signData);
        // 恒定时间比较，避免按字符提前返回的时序侧信道（hex 统一小写再比）
        boolean signOk = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        if (!signOk) {
            log.warn("[CS-SSO] 签名校验失败 appId={} externalUserId={}", appId, externalUserId);
            return errorUrl(safeRedirect);
        }

        Boolean firstUse = stringRedisTemplate.opsForValue()
                .setIfAbsent(NONCE_PREFIX + nonce.trim(), "1", NONCE_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(firstUse)) {
            log.warn("[CS-SSO] nonce 重放 appId={} nonce={}", appId, nonce);
            return errorUrl(safeRedirect);
        }

        SysUser user;
        try {
            user = resolveOrCreateUser(app, externalUserId.trim(), name);
        } catch (Exception e) {
            log.error("[CS-SSO] 建立本地账号失败 appId={} externalUserId={}", appId, externalUserId, e);
            return errorUrl(safeRedirect);
        }
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());

        // external 模式：直接带 MindCrew JWT 跳客户自有客服页
        if ("external".equalsIgnoreCase(cfg.getSsoMode()) && StringUtils.hasText(cfg.getExternalCsUrl())) {
            String target = cfg.getExternalCsUrl().trim();
            target = appendQuery(target, "mindcrewToken", token);
            target = appendQuery(target, "externalUserId", externalUserId.trim());
            return target;
        }

        // internal 模式：一次性 ticket 跳我们的 /cs
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tokenService.storeTicket(ticket, user.getId(), app.getAppId(), externalUserId.trim(), name);
        return appendQuery(safeRedirect, "ticket", ticket);
    }

    // ─────────────────────────────────────────────
    // 换取登录态
    // ─────────────────────────────────────────────

    /** POST /api/cs/sso/exchange · ticket 一次性消费 */
    public Map<String, Object> exchange(String ticket) {
        JSONObject payload = tokenService.consumeTicket(ticket);
        Long userId = payload.getLong("userId");
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "ticket 对应用户不存在");
        }
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("nickname", user.getNickname());
        userInfo.put("role", user.getRole());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("user", userInfo);
        body.put("appId", payload.getString("appId"));
        return body;
    }

    /** GET /api/cs/sso/meta · 对接方自检 */
    public Map<String, Object> meta(String appId) {
        CsConfig cfg = configService.getEntity();
        CsSsoApp app = StringUtils.hasText(appId) ? findApp(appId.trim()) : null;
        boolean enabled = Integer.valueOf(1).equals(cfg.getSsoEnabled())
                && app != null && Integer.valueOf(1).equals(app.getEnabled());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("appName", app == null ? null : app.getAppName());
        body.put("appId", app == null ? null : app.getAppId());
        return body;
    }

    // ─────────────────────────────────────────────
    // 管理端 · SSO 应用
    // ─────────────────────────────────────────────

    /** 列表（secret 打码） */
    public List<CsSsoApp> listApps() {
        List<CsSsoApp> list = appMapper.selectList(new LambdaQueryWrapper<CsSsoApp>()
                .orderByDesc(CsSsoApp::getId));
        for (CsSsoApp app : list) {
            maskSecret(app);
        }
        return list;
    }

    /** 新建（返回一次明文 appSecret） */
    @Transactional
    public Map<String, Object> createApp(String appName, String remark, String defaultRole,
                                         Integer autoCreateUser, Integer enabled) {
        if (!StringUtils.hasText(appName)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "应用名称不能为空");
        }
        String plainSecret = randomSecret();
        CsSsoApp app = new CsSsoApp();
        app.setAppName(appName.trim());
        app.setAppId("cs_" + randomHex(12));
        app.setAppSecret(aesCryptoUtils.encrypt(plainSecret));
        app.setEnabled(enabled == null ? 1 : enabled);
        app.setAutoCreateUser(autoCreateUser == null ? 1 : autoCreateUser);
        app.setDefaultRole(StringUtils.hasText(defaultRole) ? defaultRole.trim() : "user");
        app.setRemark(remark);
        appMapper.insert(app);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", maskedCopy(app));
        body.put("appSecret", plainSecret);
        return body;
    }

    /** 更新（secret 为空或打码占位时保持不变） */
    @Transactional
    public CsSsoApp updateApp(Long id, CsSsoApp dto) {
        CsSsoApp app = requireApp(id);
        if (dto == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "参数不能为空");
        }
        if (StringUtils.hasText(dto.getAppName())) app.setAppName(dto.getAppName().trim());
        if (dto.getEnabled() != null) app.setEnabled(dto.getEnabled());
        if (dto.getAutoCreateUser() != null) app.setAutoCreateUser(dto.getAutoCreateUser());
        if (StringUtils.hasText(dto.getDefaultRole())) app.setDefaultRole(dto.getDefaultRole().trim());
        if (dto.getRemark() != null) app.setRemark(dto.getRemark());
        if (StringUtils.hasText(dto.getAppSecret()) && !dto.getAppSecret().contains("...")) {
            app.setAppSecret(aesCryptoUtils.encrypt(dto.getAppSecret().trim()));
        }
        appMapper.updateById(app);
        return maskedCopy(app);
    }

    /** 逻辑删除 */
    public void deleteApp(Long id) {
        requireApp(id);
        appMapper.deleteById(id);
    }

    /** 重置密钥（返回一次明文） */
    @Transactional
    public Map<String, Object> rotateSecret(Long id) {
        CsSsoApp app = requireApp(id);
        String plainSecret = randomSecret();
        app.setAppSecret(aesCryptoUtils.encrypt(plainSecret));
        appMapper.updateById(app);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", app.getAppId());
        body.put("appSecret", plainSecret);
        return body;
    }

    public long countApps() {
        return appMapper.selectCount(new LambdaQueryWrapper<CsSsoApp>());
    }

    // ─────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────

    private CsSsoApp findApp(String appId) {
        return appMapper.selectOne(new LambdaQueryWrapper<CsSsoApp>()
                .eq(CsSsoApp::getAppId, appId)
                .last("LIMIT 1"));
    }

    private CsSsoApp findEnabledApp(String appId) {
        return appMapper.selectOne(new LambdaQueryWrapper<CsSsoApp>()
                .eq(CsSsoApp::getAppId, appId)
                .eq(CsSsoApp::getEnabled, 1)
                .last("LIMIT 1"));
    }

    private CsSsoApp requireApp(Long id) {
        CsSsoApp app = id == null ? null : appMapper.selectById(id);
        if (app == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "SSO 应用不存在");
        }
        return app;
    }

    /**
     * 外部账号 → 本地账号：
     *   1) cs_sso_user 已有映射 → 复用并刷新 last_login
     *   2) 无映射：auto_create_user=1 自动建 sys_user；否则要求本地已有同名账号
     */
    private SysUser resolveOrCreateUser(CsSsoApp app, String externalUserId, String name) {
        CsSsoUser mapping = ssoUserMapper.selectOne(new LambdaQueryWrapper<CsSsoUser>()
                .eq(CsSsoUser::getAppId, app.getAppId())
                .eq(CsSsoUser::getExternalUserId, externalUserId)
                .last("LIMIT 1"));

        if (mapping != null && mapping.getUserId() != null) {
            SysUser user = sysUserMapper.selectById(mapping.getUserId());
            if (user != null) {
                mapping.setLastLogin(LocalDateTime.now());
                if (StringUtils.hasText(name)) mapping.setExternalName(name);
                ssoUserMapper.updateById(mapping);
                return user;
            }
        }

        SysUser local = findOrCreateLocalUser(app, externalUserId, name);
        if (mapping == null) {
            mapping = new CsSsoUser();
            mapping.setAppId(app.getAppId());
            mapping.setExternalUserId(externalUserId);
            mapping.setExternalName(name);
            mapping.setUserId(local.getId());
            mapping.setLastLogin(LocalDateTime.now());
            ssoUserMapper.insert(mapping);
        } else {
            mapping.setUserId(local.getId());
            mapping.setLastLogin(LocalDateTime.now());
            if (StringUtils.hasText(name)) mapping.setExternalName(name);
            ssoUserMapper.updateById(mapping);
        }
        return local;
    }

    private SysUser findOrCreateLocalUser(CsSsoApp app, String externalUserId, String name) {
        String baseUsername = "sso_" + app.getAppId() + "_" + externalUserId;
        if (baseUsername.length() > 50) baseUsername = baseUsername.substring(0, 50);

        SysUser existing = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, baseUsername)
                .last("LIMIT 1"));
        if (existing != null) return existing;

        if (app.getAutoCreateUser() != null && app.getAutoCreateUser() == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "外部账号未绑定本地账号，请联系管理员");
        }

        String username = baseUsername;
        int suffix = 2;
        while (suffix < 100 && sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)) > 0) {
            String prefix = baseUsername.length() > 46 ? baseUsername.substring(0, 46) : baseUsername;
            username = prefix + "_" + suffix;
            suffix++;
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname(StringUtils.hasText(name) ? name : externalUserId);
        // sys_user.password 存 BCrypt；随机密码（SSO 用户走 ticket 登录，无需密码）
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRole(StringUtils.hasText(app.getDefaultRole()) ? app.getDefaultRole() : "user");
        user.setStatus(1);
        sysUserMapper.insert(user);
        log.info("[CS-SSO] 自动建号 appId={} externalUserId={} username={} id={}",
                app.getAppId(), externalUserId, username, user.getId());
        return user;
    }

    private void maskSecret(CsSsoApp app) {
        app.setAppSecret(AesCryptoUtils.mask(aesCryptoUtils.decrypt(app.getAppSecret())));
    }

    private CsSsoApp maskedCopy(CsSsoApp src) {
        CsSsoApp copy = new CsSsoApp();
        copy.setId(src.getId());
        copy.setAppName(src.getAppName());
        copy.setAppId(src.getAppId());
        copy.setAppSecret(AesCryptoUtils.mask(aesCryptoUtils.decrypt(src.getAppSecret())));
        copy.setEnabled(src.getEnabled());
        copy.setAutoCreateUser(src.getAutoCreateUser());
        copy.setDefaultRole(src.getDefaultRole());
        copy.setRemark(src.getRemark());
        copy.setCreateTime(src.getCreateTime());
        copy.setUpdateTime(src.getUpdateTime());
        copy.setDeleted(src.getDeleted());
        return copy;
    }

    /** 只允许站内相对路径，防开放重定向 */
    private static String sanitizeRedirect(String redirect) {
        String r = redirect == null ? "" : redirect.trim()
                .replace("\r", "").replace("\n", "");
        if (r.isEmpty() || !r.startsWith("/") || r.startsWith("//")
                || r.contains("\\") || r.contains("://")) {
            return DEFAULT_REDIRECT;
        }
        return r;
    }

    private static String errorUrl(String redirect) {
        return appendQuery(redirect, "cs_error", ERROR_INVALID_SIGN);
    }

    private static String appendQuery(String url, String key, String value) {
        String encoded = URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        if (url.endsWith("?") || url.endsWith("&")) {
            return url + key + "=" + encoded;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + key + "=" + encoded;
    }

    private static String randomSecret() {
        return "mk_cs_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String randomHex(int len) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.length() > len ? hex.substring(0, len) : hex;
    }
}
