package com.simon.MindCrew.support;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class KbIdsParser {

    private KbIdsParser() {
    }

    public static List<Long> parse(String rawKbIds) {
        if (rawKbIds == null || rawKbIds.isBlank()) {
            return List.of();
        }

        Set<Long> ordered = new LinkedHashSet<>();
        for (String token : rawKbIds.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("kbIds contains blank token");
            }
            try {
                ordered.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("kbIds contains non-numeric token: " + trimmed, ex);
            }
        }
        return new ArrayList<>(ordered);
    }

    public static String toJson(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return null;
        }
        return JSON.toJSONString(kbIds);
    }
}
