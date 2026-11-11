package com.simon.MindCrew.service.rag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.mapper.KbChunkMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BM25RetrieverKbScopeTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KbChunk.class
        );
    }

    @Test
    void retrieveAddsKbScopeToPrimaryQueryWhenKbIdsProvided() {
        KbChunkMapper mapper = Mockito.mock(KbChunkMapper.class);
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());
        BM25Retriever retriever = new BM25Retriever(mapper);

        retriever.retrieve("高血压 用药", null, List.of(2L, 5L), 5);

        ArgumentCaptor<LambdaQueryWrapper<KbChunk>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(mapper, Mockito.atLeastOnce()).selectList(wrapperCaptor.capture());

        LambdaQueryWrapper<KbChunk> primaryQuery = wrapperCaptor.getAllValues().get(0);
        String sqlSegment = primaryQuery.getSqlSegment();

        assertTrue(sqlSegment.contains("kb_id"), "expected kb_id scope in SQL segment but was: " + sqlSegment);
        assertTrue(primaryQuery.getParamNameValuePairs().containsValue(2L));
        assertTrue(primaryQuery.getParamNameValuePairs().containsValue(5L));
    }

    @Test
    void retrieveOmitsKbScopeWhenKbIdsMissing() {
        KbChunkMapper mapper = Mockito.mock(KbChunkMapper.class);
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());
        BM25Retriever retriever = new BM25Retriever(mapper);

        retriever.retrieve("高血压 用药", null, null, 5);

        ArgumentCaptor<LambdaQueryWrapper<KbChunk>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(mapper, Mockito.atLeastOnce()).selectList(wrapperCaptor.capture());

        LambdaQueryWrapper<KbChunk> primaryQuery = wrapperCaptor.getAllValues().get(0);
        assertTrue(!primaryQuery.getSqlSegment().contains("kb_id"),
                "did not expect kb_id scope in SQL segment but was: " + primaryQuery.getSqlSegment());
    }
}
