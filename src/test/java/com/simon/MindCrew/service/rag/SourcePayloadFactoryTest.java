package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourcePayloadFactoryTest {

    private final SourcePayloadFactory factory = new SourcePayloadFactory();

    @Test
    void buildsKnowledgeBaseAndWebSourcePayloads() {
        RetrievedChunk kbChunk = new RetrievedChunk();
        kbChunk.setSource(RetrievedChunk.Source.HYBRID);
        kbChunk.setSourceName("高血压指南");
        kbChunk.setChapter("饮食管理");
        kbChunk.setPageNumber(8);
        kbChunk.setKnowledgeBaseId(3L);
        kbChunk.setContent("知识库片段");
        kbChunk.setRerankScore(0.92f);

        RetrievedChunk webChunk = new RetrievedChunk();
        webChunk.setSource(RetrievedChunk.Source.WEB);
        webChunk.setSourceName("国家卫健委");
        webChunk.setSourceRef("https://example.com/news");
        webChunk.setContent("网页摘要");
        webChunk.setScore(0.45f);

        List<Map<String, Object>> sources = factory.build(List.of(kbChunk, webChunk));

        assertEquals(2, sources.size());
        assertEquals("knowledge_base", sources.get(0).get("type"));
        assertEquals("HYBRID", sources.get(0).get("source"));
        assertEquals(3L, sources.get(0).get("knowledgeBaseId"));
        assertNull(sources.get(0).get("url"));

        assertEquals("web", sources.get(1).get("type"));
        assertEquals("https://example.com/news", sources.get(1).get("url"));
        assertEquals("https://example.com/news", sources.get(1).get("ref"));
    }
}
