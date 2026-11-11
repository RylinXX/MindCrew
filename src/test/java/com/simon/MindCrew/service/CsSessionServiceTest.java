package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.CsSession;
import com.simon.MindCrew.mapper.CsMessageMapper;
import com.simon.MindCrew.mapper.CsSessionMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 客服中心 · 会话字段截断（防超长导致入库失败）单元测试
 */
class CsSessionServiceTest {

    private CsSessionMapper sessionMapper;
    private CsSessionService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(CsSessionMapper.class);
        service = new CsSessionService(
                sessionMapper,
                mock(CsMessageMapper.class),
                mock(SysUserMapper.class),
                mock(CsConfigService.class),
                mock(CsTokenService.class));
    }

    @Test
    void overlongVisitorFieldsAreTruncatedBeforeInsert() {
        String longKey = "k".repeat(300);
        String longName = "名".repeat(200);
        String longChannel = "c".repeat(40);
        String longAppId = "a".repeat(200);
        String longAgent = "u".repeat(900);

        service.createSession(longKey, longName, longChannel, longAppId,
                null, null, "127.0.0.1", longAgent);

        ArgumentCaptor<CsSession> captor = ArgumentCaptor.forClass(CsSession.class);
        verify(sessionMapper).insert(captor.capture());
        CsSession saved = captor.getValue();

        assertNotNull(saved.getSessionNo());
        assertEquals(128, saved.getVisitorKey().length());
        assertEquals(100, saved.getVisitorName().length());
        assertEquals(20, saved.getChannel().length());
        assertEquals(64, saved.getSourceAppId().length());
        assertEquals(500, saved.getUserAgent().length());
        assertEquals(CsSessionService.STATUS_BOT, saved.getStatus());
    }

    @Test
    void blankChannelFallsBackToWeb() {
        service.createSession(null, null, "  ", null, null, null, "127.0.0.1", null);

        ArgumentCaptor<CsSession> captor = ArgumentCaptor.forClass(CsSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertEquals("web", captor.getValue().getChannel());
        assertTrue(captor.getValue().getVisitorKey().startsWith("guest_"));
    }

    @Test
    void sessionNoIsUniqueFormat() {
        service.createSession("v1", "访客", "web", null, null, null, null, null);
        service.createSession("v2", "访客", "web", null, null, null, null, null);

        ArgumentCaptor<CsSession> captor = ArgumentCaptor.forClass(CsSession.class);
        verify(sessionMapper, times(2)).insert(captor.capture());
        String first = captor.getAllValues().get(0).getSessionNo();
        String second = captor.getAllValues().get(1).getSessionNo();
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.length() >= 14);
    }
}
