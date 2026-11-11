package com.simon.MindCrew.mcp;

import com.simon.MindCrew.config.DocmindWebSearchProperties;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WebSearchToolTest {

    @Test
    void webSearchReturnsEmptyWhenDisabled() {
        WebSearchTool tool = new WebSearchTool(new RestTemplate(), disabledProperties());

        List<RetrievedChunk> results = tool.webSearch("MindCrew", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void webSearchReturnsEmptyWhenApiKeyMissing() {
        DocmindWebSearchProperties properties = configuredProperties();
        properties.setApiKey("  ");

        WebSearchTool tool = new WebSearchTool(new RestTemplate(), properties);

        List<RetrievedChunk> results = tool.webSearch("MindCrew", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void webSearchPostsTavilyRequestAndMapsResults() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://search.example.com/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.query").value("latest mindcrew"))
                .andExpect(jsonPath("$.max_results").value(3))
                .andExpect(jsonPath("$.search_depth").value("basic"))
                .andExpect(jsonPath("$.include_answer").value(false))
                .andRespond(withSuccess("""
                        {
                          "results": [
                            {
                              "title": "Tavily Title",
                              "url": "https://example.com/result",
                              "content": "Tavily snippet",
                              "score": 0.9
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        WebSearchTool tool = new WebSearchTool(restTemplate, configuredProperties());

        List<RetrievedChunk> results = tool.webSearch("latest mindcrew", 3);

        assertEquals(1, results.size());
        RetrievedChunk chunk = results.get(0);
        assertEquals(RetrievedChunk.Source.WEB, chunk.getSource());
        assertEquals("Tavily Title", chunk.getSourceName());
        assertEquals("https://example.com/result", chunk.getSourceRef());
        assertEquals("Tavily snippet", chunk.getContent());
        server.verify();
    }

    @Test
    void webSearchDegradesToEmptyListOnRemoteError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://search.example.com/search"))
                .andRespond(withServerError());

        WebSearchTool tool = new WebSearchTool(restTemplate, configuredProperties());

        List<RetrievedChunk> results = tool.webSearch("remote failure", 2);

        assertTrue(results.isEmpty());
        server.verify();
    }

    private DocmindWebSearchProperties configuredProperties() {
        DocmindWebSearchProperties properties = new DocmindWebSearchProperties();
        properties.setEnabled(true);
        properties.setTavilyEndpoint("https://search.example.com/search");
        properties.setApiKey("test-key");
        properties.setTimeout(Duration.ofSeconds(10));
        return properties;
    }

    private DocmindWebSearchProperties disabledProperties() {
        DocmindWebSearchProperties properties = configuredProperties();
        properties.setEnabled(false);
        return properties;
    }
}
