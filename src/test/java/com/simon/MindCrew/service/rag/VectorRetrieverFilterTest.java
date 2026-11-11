package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VectorRetrieverFilterTest {

    @Test
    void buildFilterExpressionReturnsNullWhenNoCategoryOrKbScope() {
        assertNull(VectorRetriever.buildFilterExpression(null, null));
        assertNull(VectorRetriever.buildFilterExpression("", List.of()));
    }

    @Test
    void buildFilterExpressionIncludesCategoryAndKbIds() {
        String filter = VectorRetriever.buildFilterExpression("cardiology", List.of(3L, 7L, 11L));

        assertEquals(
                "category == \"cardiology\" && knowledge_base_id in [3,7,11]",
                filter
        );
    }

    @Test
    void buildFilterExpressionSupportsKbIdsWithoutCategory() {
        String filter = VectorRetriever.buildFilterExpression(null, List.of(9L, 12L));

        assertEquals("knowledge_base_id in [9,12]", filter);
    }
}
