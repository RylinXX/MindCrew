package com.simon.MindCrew.service.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 链路第2步（A路）：向量语义检索
 * 使用 Spring AI EmbeddingModel 将查询向量化，从 Milvus 检索语义相似的知识切片
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever {

    private final EmbeddingModel embeddingModel;
    private final MilvusServiceClient milvusClient;

    @Value("${milvus.enabled:true}")
    private boolean milvusEnabled;

    @Value("${milvus.collection-name}")
    private String collectionName;

    private static final int DEFAULT_TOP_K = 20;

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, int topK) {
        return retrieve(query, categoryFilter, null, topK);
    }

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, List<Long> kbIds, int topK) {
        if (!milvusEnabled) {
            log.debug("[VectorRetriever] milvus.enabled=false，跳过向量检索");
            return new ArrayList<>();
        }
        try {
            List<Float> queryVector = embed(query);
            String filter = buildFilterExpression(categoryFilter, kbIds);

            SearchParam.Builder builder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(Arrays.asList("id", "content", "category", "content_type",
                            "chapter", "page_number", "knowledge_base_id"))
                    .withTopK(topK)
                    .withVectors(List.of(queryVector))
                    .withVectorFieldName("embedding")
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                    .withParams("{\"ef\": 64}");

            if (filter != null) {
                builder.withExpr(filter);
            }

            R<SearchResults> response = milvusClient.search(builder.build());

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus 向量检索失败: {}", response.getMessage());
                return new ArrayList<>();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<RetrievedChunk> results = new ArrayList<>();

            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            for (SearchResultsWrapper.IDScore score : scores) {
                int idx = results.size();
                RetrievedChunk chunk = new RetrievedChunk();
                chunk.setId(score.getLongID() + "");
                chunk.setScore((float) score.getScore());
                chunk.setContent(getFieldValue(wrapper, "content", idx));
                chunk.setCategory(getFieldValue(wrapper, "category", idx));
                chunk.setContentType(getFieldValue(wrapper, "content_type", idx));
                chunk.setChapter(getFieldValue(wrapper, "chapter", idx));
                chunk.setSource(RetrievedChunk.Source.VECTOR);
                String kbIdStr = getFieldValue(wrapper, "knowledge_base_id", idx);
                if (kbIdStr != null && !kbIdStr.isEmpty()) {
                    try { chunk.setKnowledgeBaseId(Long.parseLong(kbIdStr)); } catch (NumberFormatException ignored) {}
                }
                String pageStr = getFieldValue(wrapper, "page_number", idx);
                if (pageStr != null && !pageStr.isEmpty()) {
                    try { chunk.setPageNumber(Integer.parseInt(pageStr)); } catch (NumberFormatException ignored) {}
                }
                results.add(chunk);
            }

            log.info("向量检索完成: query='{}', 命中={}条", query, results.size());
            return results;

        } catch (Exception e) {
            log.error("向量检索异常", e);
            return new ArrayList<>();
        }
    }

    static String buildFilterExpression(String categoryFilter, List<Long> kbIds) {
        List<String> clauses = new ArrayList<>();
        if (categoryFilter != null && !categoryFilter.isEmpty()) {
            clauses.add("category == \"" + categoryFilter + "\"");
        }
        if (kbIds != null && !kbIds.isEmpty()) {
            String kbClause = kbIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",", "knowledge_base_id in [", "]"));
            clauses.add(kbClause);
        }
        return clauses.isEmpty() ? null : String.join(" && ", clauses);
    }

    /**
     * 文本向量化：Spring AI 1.0.0 EmbeddingModel.embed() 返回 float[]，转换为 Milvus 所需的 List&lt;Float&gt;
     */
    public List<Float> embed(String text) {
        try {
            float[] raw = embeddingModel.embed(text);
            List<Float> floats = new ArrayList<>(raw.length);
            for (float f : raw) floats.add(f);
            return floats;
        } catch (Exception e) {
            log.error("文本向量化失败: {}", e.getMessage());
            // 降级：返回随机向量，避免整体流程中断
            List<Float> fallback = new ArrayList<>();
            for (int i = 0; i < 1024; i++) fallback.add((float) Math.random());
            return fallback;
        }
    }

    private String getFieldValue(SearchResultsWrapper wrapper, String fieldName, int index) {
        try {
            List<?> values = wrapper.getFieldData(fieldName, 0);
            if (values != null && index < values.size()) {
                return values.get(index) != null ? values.get(index).toString() : "";
            }
        } catch (Exception ignored) {}
        return "";
    }
}
