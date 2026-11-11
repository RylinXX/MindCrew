package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 客服中心 · 访客 token 与 SSO ticket
 *
 * 访客 token 格式（契约 3.2）：
 *   token = base64url(sessionNo + "." + expireEpochSeconds) + "." + hex(HMAC_SHA256(jwt.secret, sessionNo + "." + expire))
 *   有效期 7 天；公开接口通过请求头 X-CS-Token 携带。
 *
 * SSO ticket（契约 3.3）：Redis key cs:sso:ticket:{ticket}，TTL 300s，一次性消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsTokenService {

    private static final long VISITOR_TOKEN_TTL_SECONDS = 7L * 24 * 3600;
    private static final String TICKET_KEY_PREFIX = "cs:sso:ticket:";
    private static final long TICKET_TTL_SECONDS = 300L;

    private static final String HMAC_ALGO = "HmacSHA256";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ─────────────────────────────────────────────
    // 访客 token
    // ─────────────────────────────────────────────

    /** 签发访客 token（7 天有效） */
    public String issueVisitorToken(String sessionNo) {
        if (sessionNo == null || sessionNo.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "sessionNo 不能为空");
        }
        long expireEpochSeconds = System.currentTimeMillis() / 1000 + VISITOR_TOKEN_TTL_SECONDS;
        String payload = sessionNo + "." + expireEpochSeconds;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hmacHex(jwtSecret, payload);
    }

    /** 校验访客 token，返回 sessionNo；失败抛 401 业务异常 */
    public String verifyVisitorToken(String token) {
        if (token == null || token.isBlank()) throw visitorTokenInvalid();

        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) throw visitorTokenInvalid();

        String encoded = token.substring(0, dot);
        String sign = token.substring(dot + 1);

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw visitorTokenInvalid();
        }

        int payloadDot = payload.lastIndexOf('.');
        if (payloadDot <= 0 || payloadDot == payload.length() - 1) throw visitorTokenInvalid();

        String sessionNo = payload.substring(0, payloadDot);
        long expireEpochSeconds;
        try {
            expireEpochSeconds = Long.parseLong(payload.substring(payloadDot + 1));
        } catch (NumberFormatException e) {
            throw visitorTokenInvalid();
        }

        String expected = hmacHex(jwtSecret, payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8))) {
            throw visitorTokenInvalid();
        }
        if (expireEpochSeconds < System.currentTimeMillis() / 1000) {
            throw visitorTokenInvalid();
        }
        return sessionNo;
    }

    private BusinessException visitorTokenInvalid() {
        return new BusinessException(401, "访客凭证无效");
    }

    // ─────────────────────────────────────────────
    // SSO 一次性 ticket
    // ─────────────────────────────────────────────

    public void storeTicket(String ticket, Long userId, String appId, String externalUserId, String name) {
        JSONObject payload = new JSONObject();
        payload.put("userId", userId);
        payload.put("appId", appId);
        payload.put("externalUserId", externalUserId);
        payload.put("name", name);
        stringRedisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticket, payload.toJSONString(),
                TICKET_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 一次性消费 ticket；不存在/已使用抛 400。getAndDelete 原子取出并删除，并发安全 */
    public JSONObject consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "ticket 不能为空");
        }
        String raw = stringRedisTemplate.opsForValue().getAndDelete(TICKET_KEY_PREFIX + ticket);
        if (raw == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "ticket 已失效或已被使用");
        }
        JSONObject payload = JSON.parseObject(raw);
        if (payload == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "ticket 内容损坏");
        }
        return payload;
    }

    // ─────────────────────────────────────────────
    // HMAC 工具
    // ─────────────────────────────────────────────

    /** hex(HMAC_SHA256(secret, data)) */
    public static String hmacHex(String secret, String data) {
        return HexFormat.of().formatHex(hmac(secret, data));
    }

    /** base64(HMAC_SHA256(secret, data)) · 钉钉回调验签用 */
    public static String hmacBase64(String secret, String data) {
        return Base64.getEncoder().encodeToString(hmac(secret, data));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal((data == null ? "" : data).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败: " + e.getMessage(), e);
        }
    }
}
