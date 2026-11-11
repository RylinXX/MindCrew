package com.simon.MindCrew.service.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 链路第4步：Cross-Encoder 重排序
 * 使用阿里云 DashScope gte-rerank 模型对候选集做精细化语义排序
 * 直接 HTTP 调用，无需 Python 微服务
 */
@Slf4j
@Component
public class CrossEncoderReranker {

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${reranker.api-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String rerankApiUrl;

    @Value("${reranker.model:gte-rerank}")
    private String rerankModel;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 对候选集做 Cross-Encoder 重排序。
     * 始终调用 gte-rerank 获取真实的语义相关性分数；
     * 候选数不足时调小 top_n 即可，绝不跳过 rerank（RRF 分数不是置信度，不能直接使用）。
     */
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;

        int actualTopK = Math.min(topK, candidates.size());

        try {
            return callDashScopeRerank(query, candidates, actualTopK);
        } catch (Exception e) {
            log.warn("DashScope Rerank 调用失败，降级使用关键词排序: {}", e.getMessage());
            return fallbackRerank(query, candidates, actualTopK);
        }
    }

    /**
     * 调用阿里云 DashScope gte-rerank 接口
     * 文档: https://help.aliyun.com/document_detail/2712193.html
     */
    private List<RetrievedChunk> callDashScopeRerank(String query, List<RetrievedChunk> candidates, int topK) {
        List<String> documents = candidates.stream()
                .map(RetrievedChunk::getContent)
                .toList();

        // 构造请求体
        JSONObject input = new JSONObject();
        input.put("query", query);
        input.put("documents", documents);

        JSONObject parameters = new JSONObject();
        parameters.put("top_n", Math.min(topK, candidates.size()));
        parameters.put("return_documents", false);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", rerankModel);
        requestBody.put("input", input);
        requestBody.put("parameters", parameters);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);

        ResponseEntity<String> response = restTemplate.postForEntity(rerankApiUrl, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("DashScope Rerank 返回错误: " + response.getStatusCode());
        }

        // 解析响应: output.results[].{index, relevance_score}
        JSONObject result = JSON.parseObject(response.getBody());
        JSONArray results = result.getJSONObject("output").getJSONArray("results");

        List<RetrievedChunk> reranked = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            int originalIndex = item.getIntValue("index");
            float score = item.getFloatValue("relevance_score");

            RetrievedChunk chunk = candidates.get(originalIndex);
            chunk.setRerankScore(score);
            reranked.add(chunk);
        }

        log.info("DashScope Rerank 完成: {} 候选 → top-{}", candidates.size(), reranked.size());
        return reranked;
    }

    /**
     * 降级排序：基于关键词匹配频度（当 gte-rerank API 不可用时）。
     * 分数归一化到 0-1，不再依赖微小的 RRF 分数。
     */
    private List<RetrievedChunk> fallbackRerank(String query, List<RetrievedChunk> candidates, int topK) {
        String queryLower = query.toLowerCase();
        // 按 Unicode 分词：中文字符单字切分 + 英文单词
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{IsHan}]|[a-zA-Z0-9]+")
                .matcher(queryLower);
        while (m.find()) tokens.add(m.group());
        if (tokens.isEmpty()) tokens = java.util.List.of(queryLower);

        for (RetrievedChunk chunk : candidates) {
            String contentLower = chunk.getContent().toLowerCase();
            float matchScore = 0f;
            for (String token : tokens) {
                if (token.length() >= 1 && contentLower.contains(token)) {
                    matchScore += (float) token.length() / queryLower.length();
                }
            }
            // 归一化到 0-1：关键词匹配度即置信度
            chunk.setRerankScore(Math.min(1.0f, Math.max(0.05f, matchScore)));
        }

        candidates.sort(java.util.Comparator.comparingDouble(RetrievedChunk::getRerankScore).reversed());

        // 降级模式下做相对归一化：把最强匹配拉到 0.95，保证「有命中就不被安全阈值误判为无上下文」，
        // 由 LLM 在生成阶段判断资料是否真的足够（提示词已要求不足时如实说明）。
        float maxScore = candidates.isEmpty() ? 0f : candidates.get(0).getRerankScore();
        if (maxScore > 0f) {
            for (RetrievedChunk chunk : candidates) {
                float norm = chunk.getRerankScore() / maxScore;
                chunk.setRerankScore(Math.min(0.95f, 0.55f + 0.40f * norm));
            }
        }
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }

}
