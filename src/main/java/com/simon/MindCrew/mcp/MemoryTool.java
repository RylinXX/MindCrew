package com.simon.MindCrew.mcp;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.agent.AgentToolContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP Tool：用户偏好记忆
 * 使用 Redis 持久化用户跨会话记忆（偏好、历史主题等）
 *
 * Key 结构：user:memory:{userId}
 * Value：JSON 字符串，Map<String, Object> 格式
 * TTL：30 天
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryTool {

    private final StringRedisTemplate stringRedisTemplate;

    public static final String RECALL_TOOL_NAME = "recall_memory";
    public static final String STORE_TOOL_NAME = "store_memory";

    /** Redis Key 前缀 */
    private static final String KEY_PREFIX = "user:memory:";

    /** 记忆 TTL */
    private static final long MEMORY_TTL_DAYS = 30L;

    /**
     * 存储用户偏好记忆
     * 与已有记忆合并（不覆盖整体，仅更新传入的字段）
     *
     * @param userId 用户 ID
     * @param prefs  要存储的偏好键值对
     */
    public boolean store(String userId, Map<String, Object> prefs) {
        if (!StringUtils.hasText(userId) || prefs == null || prefs.isEmpty()) {
            return false;
        }
        try {
            String key = buildKey(userId);

            // 先读取已有记忆，与新偏好合并
            Map<String, Object> existing = recall(userId, null);
            Map<String, Object> merged = new HashMap<>(existing);
            merged.putAll(prefs);

            stringRedisTemplate.opsForValue().set(
                    key,
                    JSON.toJSONString(merged),
                    MEMORY_TTL_DAYS,
                    TimeUnit.DAYS
            );
            log.debug("[MemoryTool] store userId={} keys={}", userId, prefs.keySet());
            return true;
        } catch (Exception e) {
            log.warn("[MemoryTool] 存储记忆失败 userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 召回用户记忆
     *
     * @param userId 用户 ID
     * @param topic  主题关键词（可为 null，返回全部记忆；非空则只返回 key 包含该词的条目）
     * @return 用户记忆 Map（未找到时返回空 Map）
     */
    public Map<String, Object> recall(String userId, String topic) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            String key = buildKey(userId);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return new HashMap<>();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> memory = JSON.parseObject(json, Map.class);

            // topic 过滤
            if (topic != null && !topic.isBlank()) {
                Map<String, Object> filtered = new HashMap<>();
                memory.forEach((k, v) -> {
                    if (k.contains(topic) || (v != null && v.toString().contains(topic))) {
                        filtered.put(k, v);
                    }
                });
                return filtered;
            }

            return memory;
        } catch (Exception e) {
            log.warn("[MemoryTool] 召回记忆失败 userId={}: {}", userId, e.getMessage());
            return new HashMap<>();
        }
    }

    @Tool(name = RECALL_TOOL_NAME, description = "召回用户长期记忆：从 Redis 读取用户偏好、过敏信息、称呼等，适用于追问和个性化回答")
    public Map<String, Object> recallMemory(
            @ToolParam(description = "用户ID，不知道时传空字符串", required = false) String userId,
            @ToolParam(description = "可选主题过滤关键字", required = false) String topic) {
        // Agent 上下文激活时，userId 由系统注入，LLM 无需感知
        String effectiveUserId = (userId != null && !userId.isBlank()) ? userId
                : (AgentToolContext.isActive() ? AgentToolContext.get().getUserId() : userId);
        Map<String, Object> result = new LinkedHashMap<>(recall(effectiveUserId, topic));
        if (AgentToolContext.isActive()) {
            AgentToolContext.get().putMemory(RECALL_TOOL_NAME, result);
        }
        return result;
    }

    @Tool(name = STORE_TOOL_NAME, description = "写入用户长期记忆：仅在用户明确表达需要记住的偏好、过敏信息、称呼等长期事实时调用")
    public Map<String, Object> storeMemory(
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "需要写入的记忆键值对") Map<String, Object> prefs) {
        boolean success = store(userId, prefs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("userId", userId);
        result.put("storedKeys", prefs != null ? List.copyOf(prefs.keySet()) : List.of());
        return result;
    }

    /**
     * 清除用户记忆（用于注销/重置场景）
     */
    public void clear(String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            stringRedisTemplate.delete(buildKey(userId));
            log.info("[MemoryTool] 已清除用户记忆 userId={}", userId);
        } catch (Exception e) {
            log.warn("[MemoryTool] 清除记忆失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private String buildKey(String userId) {
        return KEY_PREFIX + userId;
    }
}
