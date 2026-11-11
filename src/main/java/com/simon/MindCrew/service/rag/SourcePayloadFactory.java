package com.simon.MindCrew.service.rag;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SourcePayloadFactory {

    public List<Map<String, Object>> build(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", i + 1);
            source.put("type", chunk.getSource() == RetrievedChunk.Source.WEB ? "web" : "knowledge_base");
            source.put("source", chunk.getSource() != null ? chunk.getSource().name() : null);
            source.put("name", defaultName(chunk));
            source.put("chapter", chunk.getChapter());
            source.put("pageNumber", chunk.getPageNumber() > 0 ? chunk.getPageNumber() : null);
            source.put("content", abbreviate(chunk.getContent(), 180));
            source.put("score", chunk.getRerankScore() > 0 ? chunk.getRerankScore() : chunk.getScore());
            source.put("knowledgeBaseId", chunk.getKnowledgeBaseId());
            source.put("ref", chunk.getSourceRef());
            if (chunk.getSource() == RetrievedChunk.Source.WEB) {
                source.put("url", chunk.getSourceRef());
            }
            // ── 时间戳级溯源元数据 ──
            source.put("mediaType", chunk.getMediaType());
            if (chunk.getStartMs() != null) source.put("startMs", chunk.getStartMs());
            if (chunk.getEndMs() != null) source.put("endMs", chunk.getEndMs());
            if (chunk.getSpeakerId() != null) source.put("speakerId", chunk.getSpeakerId());
            if (chunk.getSourceObjectName() != null) {
                source.put("sourceObjectName", chunk.getSourceObjectName());
            }
            sources.add(source);
        }
        return sources;
    }

    private String defaultName(RetrievedChunk chunk) {
        if (StringUtils.hasText(chunk.getSourceName())) {
            return chunk.getSourceName();
        }
        return chunk.getSource() == RetrievedChunk.Source.WEB ? "网页结果" : "知识库文档";
    }

    private String abbreviate(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }
}
