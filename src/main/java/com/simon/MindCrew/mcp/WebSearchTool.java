package com.simon.MindCrew.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.agent.AgentToolContext;
import com.simon.MindCrew.config.DocmindWebSearchProperties;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP Tool：互联网实时检索（Tavily）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    /** 工具注册名 */
    public static final String TOOL_NAME = "web_search";

    private final RestTemplate webSearchRestTemplate;
    private final DocmindWebSearchProperties properties;

    /**
     * 互联网检索
     *
     * @param query      检索关键词
     * @param maxResults 最多返回结果数
     * @return 检索结果（来源标注为 WEB）
     */
    @Tool(description = "互联网实时检索：调用 Tavily 联网搜索获取最新网页标题、链接和摘要，适用于新闻、政策、时效性信息查询")
    public List<RetrievedChunk> webSearch(String query, int maxResults) {
        if (!properties.isEnabled()) {
            log.info("[WebSearchTool] disabled, skip query='{}'", query);
            return List.of();
        }
        if (!StringUtils.hasText(query) || maxResults <= 0) {
            return List.of();
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("[WebSearchTool] apiKey not configured, skip query='{}'", query);
            return List.of();
        }

        try {
            JSONObject body = new JSONObject();
            body.put("query", query);
            body.put("max_results", maxResults);
            body.put("search_depth", "basic");
            body.put("include_answer", false);
            body.put("include_raw_content", false);
            body.put("include_images", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(properties.getApiKey().trim());

            HttpEntity<String> request = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> response = webSearchRestTemplate.postForEntity(
                    properties.getTavilyEndpoint(), request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
                log.warn("[WebSearchTool] non-success response status={}", response.getStatusCode());
                return List.of();
            }

            List<RetrievedChunk> results = mapResults(response.getBody(), maxResults);

            if (AgentToolContext.isActive()) {
                AgentToolContext.get().addChunks(TOOL_NAME, results);
            }

            log.info("[WebSearchTool] query='{}' maxResults={} results={}", query, maxResults, results.size());
            return results;
        } catch (Exception e) {
            log.warn("[WebSearchTool] remote search failed query='{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<RetrievedChunk> mapResults(String responseBody, int maxResults) {
        JSONObject root = JSON.parseObject(responseBody);
        if (root == null) {
            return List.of();
        }

        JSONArray items = root.getJSONArray("results");
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> results = new ArrayList<>();
        for (int i = 0; i < items.size() && results.size() < maxResults; i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) {
                continue;
            }

            String title = item.getString("title");
            String url = item.getString("url");
            String content = item.getString("content");

            if (!StringUtils.hasText(title) && !StringUtils.hasText(url) && !StringUtils.hasText(content)) {
                continue;
            }

            float score = item.getFloatValue("score");
            if (score == 0f) {
                score = 0.4f;
            }

            RetrievedChunk chunk = new RetrievedChunk();
            chunk.setId("web_" + (i + 1));
            chunk.setSource(RetrievedChunk.Source.WEB);
            chunk.setSourceName(StringUtils.hasText(title) ? title : "网页结果");
            chunk.setSourceRef(url);
            chunk.setContent(StringUtils.hasText(content) ? content : chunk.getSourceName());
            chunk.setScore(score);
            chunk.setRerankScore(score);
            results.add(chunk);
        }
        return results;
    }
}
