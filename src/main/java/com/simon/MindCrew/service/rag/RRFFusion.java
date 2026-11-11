package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RAG 链路第3步：RRF（Reciprocal Rank Fusion）融合
 * 将向量检索和 BM25 检索的两路结果融合去重
 *
 * RRF 公式：score(d) = Σ 1 / (k + rank(d))
 * k 值由 AI 配置中心动态控制（默认 60）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RRFFusion {

    private final AiConfigHolder aiConfigHolder;

    /**
     * 融合两路检索结果
     * @param vectorResults 向量检索结果（按相关性降序）
     * @param bm25Results   BM25 检索结果（按相关性降序）
     * @param topN          融合后保留的最大数量
     */
    public List<RetrievedChunk> fuse(List<RetrievedChunk> vectorResults,
                                      List<RetrievedChunk> bm25Results,
                                      int topN) {
        // 使用 content 作为去重 key（id 可能在不同路径中表示不同）
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 计算向量检索路的 RRF 贡献
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            RetrievedChunk chunk = vectorResults.get(rank);
            String key = chunk.getContent().substring(0, Math.min(50, chunk.getContent().length()));
            double rrfScore = 1.0 / (aiConfigHolder.getInt("rag.rrf_k_constant") + rank + 1);

            chunkMap.putIfAbsent(key, chunk);
            rrfScores.merge(key, rrfScore, Double::sum);
        }

        // 计算 BM25 路的 RRF 贡献
        for (int rank = 0; rank < bm25Results.size(); rank++) {
            RetrievedChunk chunk = bm25Results.get(rank);
            String key = chunk.getContent().substring(0, Math.min(50, chunk.getContent().length()));
            double rrfScore = 1.0 / (aiConfigHolder.getInt("rag.rrf_k_constant") + rank + 1);

            if (!chunkMap.containsKey(key)) {
                chunkMap.put(key, chunk);
            } else {
                // 标记为混合来源
                chunkMap.get(key).setSource(RetrievedChunk.Source.HYBRID);
            }
            rrfScores.merge(key, rrfScore, Double::sum);
        }

        // 按 RRF 分数降序排序
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(rrfScores.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 构建最终结果
        List<RetrievedChunk> fused = new ArrayList<>();
        for (int i = 0; i < Math.min(sortedEntries.size(), topN); i++) {
            String key = sortedEntries.get(i).getKey();
            RetrievedChunk chunk = chunkMap.get(key);
            chunk.setScore((float) (double) sortedEntries.get(i).getValue());
            chunk.setRrfRank(i + 1);
            fused.add(chunk);
        }

        log.info("RRF融合: 向量路={}, BM25路={}, 融合后={}",
                vectorResults.size(), bm25Results.size(), fused.size());
        return fused;
    }
}
