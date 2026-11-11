package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * RAG 高频问题缓存服务
 *
 * <p>策略：
 * <ol>
 *   <li>每次提问先对归一化问题计数（Redis Sorted Set）</li>
 *   <li>频次 &ge; FREQ_THRESHOLD 时将答案写入 Redis Hash（TTL CACHE_TTL_HOURS）</li>
 *   <li>后续相同问题直接返回缓存，跳过全量 RAG 流水线</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AiConfigHolder aiConfigHolder;

    /** 问题频次 Sorted Set key */
    private static final String FREQ_KEY = "rag:freq";

    /** 缓存 key 前缀 */
    private static final String CACHE_PREFIX = "rag:cache:";

    // ======================== 公开 API ========================

    /**
     * 对问题文本做归一化处理（去首尾空格 + 折叠多余空白 + 小写）
     */
    public String normalize(String question) {
        if (question == null) return "";
        return question.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * 自增问题频次，返回自增后的频次值
     */
    public long incrementFrequency(String normalized) {
        Double score = redisTemplate.opsForZSet().incrementScore(FREQ_KEY, normalized, 1);
        return score == null ? 1L : score.longValue();
    }

    /**
     * 查询问题当前频次（不自增）
     */
    public long getFrequency(String normalized) {
        Double score = redisTemplate.opsForZSet().score(FREQ_KEY, normalized);
        return score == null ? 0L : score.longValue();
    }

    /**
     * 从 Redis 获取缓存结果，未命中返回 null
     */
    public RagCachedResult getCache(String normalized) {
        String key = cacheKey(normalized);
        Object val = redisTemplate.opsForValue().get(key);
        if (val instanceof RagCachedResult result) {
            log.info("[RAG Cache] HIT  key={}", key);
            return result;
        }
        return null;
    }

    /**
     * 将 RAG 结果写入缓存（仅当频次 &ge; 阈值时才写入）
     */
    public void putCacheIfFrequent(String normalized, RagCachedResult result, long frequency) {
        int freqThreshold = aiConfigHolder.getInt("cache.freq_threshold");
        long ttlHours = aiConfigHolder.getInt("cache.ttl_hours");
        if (frequency >= freqThreshold) {
            String key = cacheKey(normalized);
            redisTemplate.opsForValue().set(key, result, ttlHours, TimeUnit.HOURS);
            log.info("[RAG Cache] WRITE key={} freq={} ttl={}h", key, frequency, ttlHours);
        }
    }

    /**
     * 主动刷新指定问题的缓存（例如知识库更新后调用）
     */
    public void evictCache(String normalized) {
        redisTemplate.delete(cacheKey(normalized));
        log.info("[RAG Cache] EVICT key={}", cacheKey(normalized));
    }

    /**
     * 查询频次 Top-N 的问题（供管理端监控使用）
     */
    public Set<ZSetOperations.TypedTuple<Object>> getTopFrequentQuestions(int topN) {
        return redisTemplate.opsForZSet().reverseRangeWithScores(FREQ_KEY, 0, topN - 1);
    }

    // ======================== 私有方法 ========================

    private String cacheKey(String normalized) {
        String md5 = DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
        return CACHE_PREFIX + md5;
    }
}
