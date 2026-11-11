package com.simon.MindCrew.mcp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryToolTest {

    @Test
    void recallMemorySupportsTopicFilter() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:memory:user-1"))
                .thenReturn("{\"profile.nickname\":\"老张\",\"health.allergy\":\"青霉素\"}");

        MemoryTool tool = new MemoryTool(redisTemplate);

        Map<String, Object> memory = tool.recallMemory("user-1", "allergy");

        assertEquals(1, memory.size());
        assertEquals("青霉素", memory.get("health.allergy"));
    }

    @Test
    void storeMemoryReturnsConfirmationAndPersistsMergedPayload() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:memory:user-2"))
                .thenReturn("{\"profile.nickname\":\"小王\"}");

        MemoryTool tool = new MemoryTool(redisTemplate);

        Map<String, Object> result = tool.storeMemory("user-2", Map.of("preference.likes", "低盐饮食"));

        assertTrue((Boolean) result.get("success"));
        assertEquals("user-2", result.get("userId"));
        assertEquals(1, ((java.util.List<?>) result.get("storedKeys")).size());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("user:memory:user-2"), jsonCaptor.capture(), eq(30L), eq(TimeUnit.DAYS));
        String storedJson = jsonCaptor.getValue();
        assertTrue(storedJson.contains("profile.nickname"));
        assertTrue(storedJson.contains("preference.likes"));
    }

    @Test
    void storeMemoryRejectsEmptyPayload() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        MemoryTool tool = new MemoryTool(redisTemplate);

        Map<String, Object> result = tool.storeMemory("user-3", Map.of());

        assertFalse((Boolean) result.get("success"));
        assertEquals(0, ((java.util.List<?>) result.get("storedKeys")).size());
    }
}
