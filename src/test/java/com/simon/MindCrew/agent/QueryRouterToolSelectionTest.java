package com.simon.MindCrew.agent;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRouterToolSelectionTest {

    @Test
    void followupQueriesRouteToRecallMemoryAndDocSearch() {
        QueryRouter router = routerReturning("followup");

        QueryRouter.IntentResult result = router.route("刚才你说的那个结论是什么");

        assertEquals(QueryRouter.FOLLOWUP, result.getIntentType());
        assertEquals(List.of("recall_memory", "doc_search"), result.getTools());
    }

    @Test
    void compoundQueriesIncludeWebSearch() {
        QueryRouter router = routerReturning("compound");

        QueryRouter.IntentResult result = router.route("这个方案和上一个方案有什么区别");

        assertEquals(QueryRouter.COMPOUND, result.getIntentType());
        assertTrue(result.getTools().contains("web_search"));
    }

    @Test
    void retrievedChunkSourceSupportsWeb() {
        RetrievedChunk.Source source = RetrievedChunk.Source.valueOf("WEB");

        assertNotNull(source);
        assertEquals("WEB", source.name());
    }

    private QueryRouter routerReturning(String response) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn(response);

        AiConfigHolder aiConfigHolder = mock(AiConfigHolder.class);
        when(aiConfigHolder.getChatModel()).thenReturn(chatModel);

        return new QueryRouter(aiConfigHolder);
    }
}



