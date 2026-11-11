package com.simon.MindCrew.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从当前轮用户输入中抽取明确、长期有效的偏好或事实。
 * 只处理显式表达，避免把普通问题错误写入长期记忆。
 */
@Component
public class ExplicitMemoryExtractor {

    private static final Pattern LIKE_PATTERN = Pattern.compile("我喜欢([^，。；!?？]+)");
    private static final Pattern DISLIKE_PATTERN = Pattern.compile("我不喜欢([^，。；!?？]+)");
    private static final Pattern ALLERGY_PATTERN = Pattern.compile("我对([^，。；!?？]+)过敏");
    private static final Pattern STYLE_PATTERN = Pattern.compile("请用([^，。；!?？]+)(回答|回复|讲解)");
    private static final Pattern NAME_PATTERN = Pattern.compile("(叫我|称呼我)([^，。；!?？]+)");

    public Map<String, Object> extract(String query) {
        Map<String, Object> memory = new LinkedHashMap<>();
        if (!StringUtils.hasText(query)) {
            return memory;
        }

        putIfMatched(memory, "preference.likes", query, LIKE_PATTERN);
        putIfMatched(memory, "preference.dislikes", query, DISLIKE_PATTERN);
        putIfMatched(memory, "health.allergy", query, ALLERGY_PATTERN);
        putIfMatched(memory, "response.style", query, STYLE_PATTERN, 1);
        putIfMatched(memory, "profile.nickname", query, NAME_PATTERN, 2);

        return memory;
    }

    private void putIfMatched(Map<String, Object> memory, String key, String query, Pattern pattern) {
        putIfMatched(memory, key, query, pattern, 1);
    }

    private void putIfMatched(Map<String, Object> memory, String key, String query, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String value = matcher.group(group);
            if (StringUtils.hasText(value)) {
                memory.put(key, value.trim());
            }
        }
    }
}
