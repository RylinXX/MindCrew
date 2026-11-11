package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.CsConfig;
import com.simon.MindCrew.mapper.CsConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客服中心 · 页面配置解析与公开视图单元测试
 */
class CsConfigServiceTest {

    private CsConfigService newService(CsConfig config) {
        CsConfigMapper mapper = mock(CsConfigMapper.class);
        when(mapper.selectById(1L)).thenReturn(config);
        return new CsConfigService(mapper);
    }

    private CsConfig sample() {
        CsConfig c = new CsConfig();
        c.setId(1L);
        c.setPageTitle("智能客服");
        c.setQuickQuestions("[\"怎么配置权限？\",\"支持哪些格式？\"]");
        c.setHandoffKeywords("[\"转人工\",\"人工客服\"]");
        c.setKbIds("[1,2,3]");
        c.setWorkHours("not-a-time-range");
        c.setAiEnabled(1);
        c.setSatisfactionEnabled(1);
        c.setShowBrand(1);
        c.setSsoEnabled(0);
        c.setSsoMode("internal");
        c.setGreetingAgentName("小梦");
        return c;
    }

    @Test
    void parseStringListIsFaultTolerant() {
        assertEquals(List.of("a", "b"), CsConfigService.parseStringList("[\"a\",\"b\"]"));
        assertTrue(CsConfigService.parseStringList(null).isEmpty());
        assertTrue(CsConfigService.parseStringList("").isEmpty());
        assertTrue(CsConfigService.parseStringList("not-json").isEmpty());
        assertTrue(CsConfigService.parseStringList("[]").isEmpty());
    }

    @Test
    void handoffKeywordMatches() {
        CsConfigService service = newService(sample());
        CsConfig cfg = service.getEntity();
        assertTrue(service.hitHandoffKeyword("你好，我要转人工", cfg));
        assertFalse(service.hitHandoffKeyword("怎么上传文档", cfg));
        assertFalse(service.hitHandoffKeyword(null, cfg));
    }

    @Test
    void invalidWorkHoursTreatedAsOnline() {
        CsConfigService service = newService(sample());
        assertTrue(service.isOnline(service.getEntity()));
    }

    @Test
    void publicViewParsesArraysAndHidesInternalFields() {
        CsConfigService service = newService(sample());
        Map<String, Object> view = service.publicView();

        assertEquals("智能客服", view.get("pageTitle"));
        assertEquals(List.of("怎么配置权限？", "支持哪些格式？"), view.get("quickQuestions"));
        assertTrue(view.containsKey("online"));
        assertFalse(view.containsKey("kbIds"));
        assertFalse(view.containsKey("handoffKeywords"));
    }

    @Test
    void kbIdsParsedAsLongList() {
        CsConfigService service = newService(sample());
        assertEquals(List.of(1L, 2L, 3L), service.kbIds(service.getEntity()));
    }
}
