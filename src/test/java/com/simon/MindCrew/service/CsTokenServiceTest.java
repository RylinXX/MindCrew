package com.simon.MindCrew.service;

import com.simon.MindCrew.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客服中心 · 访客 token 单元测试（不连 Redis / 不启 Spring 容器）
 */
class CsTokenServiceTest {

    private static final String SECRET = "unit-test-secret-please-change-32chars";

    private CsTokenService service;

    @BeforeEach
    void setUp() {
        service = new CsTokenService(org.mockito.Mockito.mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(service, "jwtSecret", SECRET);
    }

    @Test
    void visitorTokenRoundTripReturnsSessionNo() {
        String token = service.issueVisitorToken("sess-1001");
        assertNotNull(token);
        assertEquals("sess-1001", service.verifyVisitorToken(token));
    }

    @Test
    void tamperedSignatureRejectedWith401() {
        String token = service.issueVisitorToken("sess-1002");
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == '0' ? '1' : '0');
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyVisitorToken(tampered));
        assertEquals(401, ex.getCode());
    }

    @Test
    void expiredTokenRejected() {
        String sessionNo = "sess-1003";
        long expired = System.currentTimeMillis() / 1000 - 60;
        String payload = sessionNo + "." + expired;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String token = encoded + "." + CsTokenService.hmacHex(SECRET, payload);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyVisitorToken(token));
        assertEquals(401, ex.getCode());
    }

    @Test
    void blankTokenRejected() {
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.verifyVisitorToken(" ")).getCode());
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.verifyVisitorToken(null)).getCode());
    }

    @Test
    void hmacHexMatchesKnownVector() {
        assertEquals("1b2c16b75bd2a870c114153ccda5bcfca63314bc722fa160d690de133ccbb9db",
                CsTokenService.hmacHex("secret", "data"));
        assertEquals(64, CsTokenService.hmacHex("secret", "数据").length());
    }
}
