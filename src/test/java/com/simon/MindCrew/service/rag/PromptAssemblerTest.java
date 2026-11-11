package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    private final PromptAssembler promptAssembler = new PromptAssembler();

    @Test
    void assembleIncludesMemoryAndWebCitations() {
        RetrievedChunk kbChunk = new RetrievedChunk();
        kbChunk.setSource(RetrievedChunk.Source.VECTOR);
        kbChunk.setSourceName("高血压指南");
        kbChunk.setChapter("药物治疗");
        kbChunk.setPageNumber(12);
        kbChunk.setContent("知识库内容");

        RetrievedChunk webChunk = new RetrievedChunk();
        webChunk.setSource(RetrievedChunk.Source.WEB);
        webChunk.setSourceName("国家卫健委通知");
        webChunk.setSourceRef("https://example.com/guideline");
        webChunk.setContent("网页摘要");

        String prompt = promptAssembler.assemble(
                "最新高血压指南是什么",
                List.of(kbChunk, webChunk),
                Map.of("profile.nickname", "老张", "health.allergy", "青霉素"),
                null,
                "用户：之前问过高血压"
        );

        assertTrue(prompt.contains("profile.nickname: 老张"));
        assertTrue(prompt.contains("health.allergy: 青霉素"));
        assertTrue(prompt.contains("[1] 知识库《高血压指南》 - 药物治疗 第12页"));
        assertTrue(prompt.contains("[2] 网页《国家卫健委通知》 https://example.com/guideline"));
    }
}
