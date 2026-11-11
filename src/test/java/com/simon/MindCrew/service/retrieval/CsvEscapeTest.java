package com.simon.MindCrew.service.retrieval;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检索分析 · CSV 导出转义与公式注入防护单元测试
 */
class CsvEscapeTest {

    private static String escape(String value) throws Exception {
        Method m = RetrievalAnalyticsService.class.getDeclaredMethod("csvEscape", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, new Object[]{value});
    }

    @Test
    void prefixesFormulaInjectionPayloads() throws Exception {
        assertEquals("'=cmd|' /C calc'!A0", escape("=cmd|' /C calc'!A0"));
        assertEquals("'+1+1", escape("+1+1"));
        assertEquals("'-2", escape("-2"));
        assertEquals("'@SUM(A1)", escape("@SUM(A1)"));
    }

    @Test
    void quotesCommasAndDoubleQuotes() throws Exception {
        assertEquals("\"a,b\"", escape("a,b"));
        assertEquals("\"he said \"\"hi\"\"\"", escape("he said \"hi\""));
    }

    @Test
    void stripsNewlinesAndHandlesNull() throws Exception {
        assertEquals("a b", escape("a\nb"));
        assertEquals("", escape(null));
        assertEquals("正常问题", escape("正常问题"));
    }
}
