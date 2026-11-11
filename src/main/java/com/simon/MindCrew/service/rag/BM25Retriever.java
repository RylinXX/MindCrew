package com.simon.MindCrew.service.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.mapper.KbChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 链路第2步（B路）：中文 BM25 关键词检索
 * 优先使用 MySQL FULLTEXT(n-gram) 预召回，失败时降级到应用内中文分词 + BM25 打分。
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class BM25Retriever {

    private static final int DEFAULT_TOP_K = 20;
    private static final int DEFAULT_CANDIDATE_LIMIT = 400;
    private static final double BM25_K1 = 1.5d;
    private static final double BM25_B = 0.75d;

    private final KbChunkMapper kbChunkMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ChineseTextTokenizer chineseTextTokenizer;

    BM25Retriever(KbChunkMapper kbChunkMapper) {
        this(kbChunkMapper, new JdbcTemplate() {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                throw new UnsupportedOperationException("FULLTEXT disabled in test constructor");
            }
        }, new ChineseTextTokenizer());
    }

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, int topK) {
        return retrieve(query, categoryFilter, null, topK);
    }

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, List<Long> kbIds, int topK) {
        try {
            List<String> queryTerms = chineseTextTokenizer.tokenize(query);
            if (queryTerms.isEmpty()) {
                log.warn("BM25：未提取到有效中文检索词");
                return new ArrayList<>();
            }

            int resultLimit = topK > 0 ? topK : DEFAULT_TOP_K;
            int candidateLimit = Math.max(resultLimit * 10, DEFAULT_CANDIDATE_LIMIT);

            List<KbChunk> candidates = searchCandidatesByFullText(queryTerms, kbIds, candidateLimit);
            if (candidates.isEmpty()) {
                candidates = searchCandidatesByTokenFallback(queryTerms, kbIds, candidateLimit);
            }
            if (candidates.isEmpty()) {
                log.info("BM25检索完成: queryTerms={}，命中=0", queryTerms);
                return new ArrayList<>();
            }

            List<RetrievedChunk> scored = scoreWithBm25(queryTerms, candidates);
            List<RetrievedChunk> results = scored.subList(0, Math.min(resultLimit, scored.size()));
            log.info("BM25检索完成: queryTerms={}，候选={}，命中={}", queryTerms, candidates.size(), results.size());
            return results;
        } catch (Exception e) {
            log.warn("BM25 检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<KbChunk> searchCandidatesByFullText(List<String> queryTerms, List<Long> kbIds, int candidateLimit) {
        try {
            String searchText = String.join(" ", queryTerms);
            StringBuilder sql = new StringBuilder("""
                    SELECT id, kb_id, content, chunk_index, metadata, vector_id
                    FROM kb_chunk
                    WHERE MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE)
                    """);
            List<Object> args = new ArrayList<>();
            args.add(searchText);

            if (kbIds != null && !kbIds.isEmpty()) {
                sql.append(" AND kb_id IN (");
                sql.append(kbIds.stream().map(id -> "?").collect(Collectors.joining(",")));
                sql.append(")");
                args.addAll(kbIds);
            }
            sql.append(" ORDER BY MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) DESC LIMIT ?");
            args.add(searchText);
            args.add(candidateLimit);

            return jdbcTemplate.query(sql.toString(), this::mapChunkRow, args.toArray());
        } catch (Exception e) {
            log.debug("FULLTEXT 预召回不可用，回退应用内 BM25: {}", e.getMessage());
            return List.of();
        }
    }

    private List<KbChunk> searchCandidatesByTokenFallback(List<String> queryTerms, List<Long> kbIds, int candidateLimit) {
        LambdaQueryWrapper<KbChunk> wrapper = new LambdaQueryWrapper<>();
        applyKbScope(wrapper, kbIds);

        List<String> terms = queryTerms.stream()
                .filter(term -> term.length() >= 2)
                .limit(8)
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }

        wrapper.and(condition -> {
            boolean first = true;
            for (String term : terms) {
                if (first) {
                    condition.like(KbChunk::getContent, term);
                    first = false;
                } else {
                    condition.or().like(KbChunk::getContent, term);
                }
            }
        }).last("LIMIT " + candidateLimit);

        return kbChunkMapper.selectList(wrapper);
    }

    private void applyKbScope(LambdaQueryWrapper<KbChunk> wrapper, List<Long> kbIds) {
        if (kbIds != null && !kbIds.isEmpty()) {
            wrapper.in(KbChunk::getKbId, kbIds);
        }
    }

    private List<RetrievedChunk> scoreWithBm25(List<String> queryTerms, List<KbChunk> candidates) {
        List<DocumentTerms> docs = new ArrayList<>(candidates.size());
        Map<String, Integer> docFreq = new HashMap<>();
        double avgDocLength = 0d;

        for (KbChunk candidate : candidates) {
            List<String> tokens = chineseTextTokenizer.tokenize(candidate.getContent());
            if (tokens.isEmpty()) {
                continue;
            }

            avgDocLength += tokens.size();
            Map<String, Integer> tf = new HashMap<>();
            Set<String> unique = new LinkedHashSet<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
                unique.add(token);
            }
            unique.forEach(token -> docFreq.merge(token, 1, Integer::sum));
            docs.add(new DocumentTerms(candidate, tf, tokens.size()));
        }

        if (docs.isEmpty()) {
            return new ArrayList<>();
        }
        avgDocLength /= docs.size();

        List<RetrievedChunk> results = new ArrayList<>(docs.size());
        for (DocumentTerms doc : docs) {
            double score = computeBm25(queryTerms, doc, docFreq, docs.size(), avgDocLength);
            if (score <= 0d) {
                continue;
            }

            RetrievedChunk chunk = new RetrievedChunk();
            chunk.setId(String.valueOf(doc.chunk().getId()));
            chunk.setContent(doc.chunk().getContent());
            chunk.setKnowledgeBaseId(doc.chunk().getKbId());
            chunk.setScore((float) score);
            chunk.setRerankScore((float) score);
            chunk.setSource(RetrievedChunk.Source.BM25);
            applyMetadata(chunk, doc.chunk().getMetadata());
            results.add(chunk);
        }

        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results;
    }

    private double computeBm25(List<String> queryTerms,
                               DocumentTerms doc,
                               Map<String, Integer> docFreq,
                               int docCount,
                               double avgDocLength) {
        double score = 0d;
        double docLength = Math.max(1d, doc.length());

        for (String term : queryTerms) {
            int tf = doc.termFrequency().getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }

            int df = docFreq.getOrDefault(term, 0);
            double idf = Math.log(1d + (docCount - df + 0.5d) / (df + 0.5d));
            double numerator = tf * (BM25_K1 + 1d);
            double denominator = tf + BM25_K1 * (1d - BM25_B + BM25_B * docLength / Math.max(1d, avgDocLength));
            score += idf * numerator / denominator;
        }

        score += phraseCoverageBonus(queryTerms, doc.chunk().getContent());
        return score;
    }

    private double phraseCoverageBonus(List<String> queryTerms, String content) {
        if (content == null || content.isBlank()) {
            return 0d;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        int hitTerms = 0;
        for (String term : queryTerms) {
            if (normalized.contains(term)) {
                hitTerms++;
            }
        }
        return hitTerms * 0.08d;
    }

    private void applyMetadata(RetrievedChunk chunk, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        try {
            JSONObject metadata = JSON.parseObject(metadataJson);
            chunk.setContentType(metadata.getString("contentType"));
            chunk.setChapter(metadata.getString("chapter"));
            Integer pageNumber = metadata.getInteger("pageNumber");
            if (pageNumber != null) {
                chunk.setPageNumber(pageNumber);
            }

            // 时间戳溯源（音视频）
            Long startMs = metadata.getLong("startMs");
            Long endMs = metadata.getLong("endMs");
            String speakerId = metadata.getString("speakerId");
            String sourceObjectName = metadata.getString("sourceObjectName");
            if (startMs != null) chunk.setStartMs(startMs);
            if (endMs != null) chunk.setEndMs(endMs);
            if (speakerId != null) chunk.setSpeakerId(speakerId);
            if (sourceObjectName != null) chunk.setSourceObjectName(sourceObjectName);

            // 推断 mediaType（根据 contentType / 文件名后缀）
            String mediaType = inferMediaType(metadata.getString("contentType"));
            if (mediaType != null) chunk.setMediaType(mediaType);
        } catch (Exception e) {
            log.debug("解析切片 metadata 失败: {}", e.getMessage());
        }
    }

    private String inferMediaType(String contentType) {
        if (contentType == null) return null;
        // chunk metadata 里 contentType 在音频处理时显式设为 "audio"；其他场景留空
        return switch (contentType) {
            case "audio" -> "audio";
            case "video" -> "video";
            case "image" -> "image";
            default -> null;   // 文档类不在这层判断
        };
    }

    private KbChunk mapChunkRow(ResultSet rs, int rowNum) throws SQLException {
        KbChunk chunk = new KbChunk();
        chunk.setId(rs.getLong("id"));
        chunk.setKbId(rs.getLong("kb_id"));
        chunk.setContent(rs.getString("content"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setMetadata(rs.getString("metadata"));
        chunk.setVectorId(rs.getString("vector_id"));
        return chunk;
    }

    private record DocumentTerms(KbChunk chunk, Map<String, Integer> termFrequency, int length) {
    }
}
