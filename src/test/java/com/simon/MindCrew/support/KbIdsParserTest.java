package com.simon.MindCrew.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KbIdsParserTest {

    @Test
    void parseShouldTrimDeduplicateAndPreserveOrder() {
        List<Long> kbIds = KbIdsParser.parse(" 1, 2, 1, 3 , 2 ");

        assertEquals(List.of(1L, 2L, 3L), kbIds);
    }

    @Test
    void parseShouldReturnEmptyListForBlankInput() {
        assertEquals(List.of(), KbIdsParser.parse("   "));
        assertEquals(List.of(), KbIdsParser.parse(null));
    }

    @Test
    void parseShouldRejectNonNumericTokens() {
        assertThrows(IllegalArgumentException.class, () -> KbIdsParser.parse("1,abc,2"));
    }

    @Test
    void toJsonShouldReturnNullForEmptyInput() {
        assertNull(KbIdsParser.toJson(List.of()));
        assertNull(KbIdsParser.toJson(null));
    }

    @Test
    void toJsonShouldSerializeKbIds() {
        assertEquals("[1,2,3]", KbIdsParser.toJson(List.of(1L, 2L, 3L)));
    }
}
