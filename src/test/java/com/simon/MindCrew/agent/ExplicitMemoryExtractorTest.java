package com.simon.MindCrew.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitMemoryExtractorTest {

    private final ExplicitMemoryExtractor extractor = new ExplicitMemoryExtractor();

    @Test
    void extractsExplicitLongTermPreferences() {
        Map<String, Object> result = extractor.extract("请记住，我喜欢低盐饮食，而且我对青霉素过敏，叫我老张。");

        assertEquals("低盐饮食", result.get("preference.likes"));
        assertEquals("青霉素", result.get("health.allergy"));
        assertEquals("老张", result.get("profile.nickname"));
    }

    @Test
    void ignoresOrdinaryQuestions() {
        Map<String, Object> result = extractor.extract("高血压平时应该怎么吃药？");

        assertTrue(result.isEmpty());
    }
}
