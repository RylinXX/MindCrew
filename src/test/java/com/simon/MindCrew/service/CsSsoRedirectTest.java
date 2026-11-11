package com.simon.MindCrew.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSO 回跳地址校验（防开放重定向）单元测试
 */
class CsSsoRedirectTest {

    private static String sanitize(String input) throws Exception {
        Method m = CsSsoService.class.getDeclaredMethod("sanitizeRedirect", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, new Object[]{input});
    }

    @Test
    void rejectsOpenRedirectTargets() throws Exception {
        assertEquals("/cs", sanitize(null));
        assertEquals("/cs", sanitize(""));
        assertEquals("/cs", sanitize("//evil.com"));
        assertEquals("/cs", sanitize("http://evil.com"));
        assertEquals("/cs", sanitize("https://evil.com/cs"));
        assertEquals("/cs", sanitize("javascript:alert(1)"));
        assertEquals("/cs", sanitize("/cs\\evil"));
    }

    @Test
    void keepsInternalRelativePath() throws Exception {
        assertEquals("/cs", sanitize("/cs"));
        assertEquals("/cs?from=sso", sanitize("/cs?from=sso"));
    }
}
