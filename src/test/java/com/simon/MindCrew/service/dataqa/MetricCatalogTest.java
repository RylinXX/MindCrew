package com.simon.MindCrew.service.dataqa;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 问数 · 指标目录与参数钳制单元测试
 */
class MetricCatalogTest {

    private static int clamp(Integer value) throws Exception {
        Method m = DataQaService.class.getDeclaredMethod("clamp", Integer.class, int.class, int.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, value, 1, 90, 7);
    }

    @Test
    void catalogContainsNineWhitelistMetrics() {
        assertEquals(9, MetricCatalog.all().size());
        assertEquals(9, MetricCatalog.describeAll().size());
        List<String> keys = MetricCatalog.all().stream().map(MetricCatalog.Metric::key).toList();
        assertTrue(keys.containsAll(List.of("question_count", "active_users", "token_usage",
                "cost_by_model", "latency_trend", "feedback_ratio", "kb_ranking", "top_queries", "usage_by_day")));
        assertTrue(MetricCatalog.contains("question_count"));
        assertFalse(MetricCatalog.contains("drop_table"));
    }

    @Test
    void describeAllExposesFrontendFields() {
        for (Map<String, Object> item : MetricCatalog.describeAll()) {
            assertNotNull(item.get("key"));
            assertNotNull(item.get("name"));
            assertNotNull(item.get("description"));
            assertNotNull(item.get("chartType"));
            assertNotNull(item.get("example"));
            assertFalse(String.valueOf(item.get("key")).isBlank());
        }
    }

    @Test
    void daysAndLimitAreClamped() throws Exception {
        assertEquals(90, clamp(999));
        assertEquals(1, clamp(-5));
        assertEquals(7, clamp(null));
        assertEquals(30, clamp(30));
    }
}
