package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RRF 融合单元测试（多路结果去重、排序、TopN）
 */
class RRFFusionTest {

    private RetrievedChunk chunk(String content) {
        RetrievedChunk c = new RetrievedChunk();
        c.setContent(content);
        c.setScore(0.5f);
        return c;
    }

    @Test
    void fusesByRankAndMarksHybrid() {
        AiConfigHolder holder = mock(AiConfigHolder.class);
        when(holder.getInt("rag.rrf_k_constant")).thenReturn(60);
        RRFFusion fusion = new RRFFusion(holder);

        RetrievedChunk a = chunk("A 文档内容片段一");
        RetrievedChunk b = chunk("B 文档内容片段二");
        RetrievedChunk c = chunk("C 文档内容片段三");

        List<RetrievedChunk> fused = fusion.fuse(List.of(a, b), List.of(b, c), 10);

        assertEquals(3, fused.size());
        assertEquals("B 文档内容片段二", fused.get(0).getContent(), "两路都命中的文档应排第一");
        assertEquals(1, fused.get(0).getRrfRank());
        assertEquals(RetrievedChunk.Source.HYBRID, fused.get(0).getSource());
        assertTrue(fused.get(0).getScore() > 0);
    }

    @Test
    void respectsTopN() {
        AiConfigHolder holder = mock(AiConfigHolder.class);
        when(holder.getInt("rag.rrf_k_constant")).thenReturn(60);
        RRFFusion fusion = new RRFFusion(holder);

        List<RetrievedChunk> vector = List.of(chunk("A"), chunk("B"), chunk("C"));
        assertEquals(2, fusion.fuse(vector, List.of(), 2).size());
    }

    @Test
    void emptyInputsReturnEmpty() {
        AiConfigHolder holder = mock(AiConfigHolder.class);
        when(holder.getInt("rag.rrf_k_constant")).thenReturn(60);
        RRFFusion fusion = new RRFFusion(holder);
        assertTrue(fusion.fuse(List.of(), List.of(), 10).isEmpty());
    }
}
