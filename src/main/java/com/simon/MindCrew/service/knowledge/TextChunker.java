package com.simon.MindCrew.service.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本智能切分器
 * 策略：按段落切分 + 滑动窗口合并（保证语义完整性）
 * - 目标切片大小：300-500字
 * - 重叠窗口：50字（确保上下文连贯）
 */
@Slf4j
@Component
public class TextChunker {

    private static final int TARGET_CHUNK_SIZE = 400;   // 目标切片字数
    private static final int MAX_CHUNK_SIZE = 600;      // 最大切片字数
    private static final int OVERLAP_SIZE = 50;         // 重叠字数

    /**
     * 切分文本为 Chunk 列表
     */
    public List<TextChunk> chunk(String text, Long knowledgeBaseId, String category) {
        // 1. 预处理：规范化空白字符
        text = text.replaceAll("\r\n", "\n")
                   .replaceAll("\r", "\n")
                   .replaceAll("　", "  ")    // 全角空格
                   .replaceAll("\n{3,}", "\n\n");  // 多个空行压缩为两个

        // 2. 按段落分割
        String[] paragraphs = text.split("\n\n");

        // 3. 合并短段落，切分超长段落
        List<String> rawChunks = mergeParagraphs(paragraphs);

        // 4. 构建 TextChunk 对象
        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < rawChunks.size(); i++) {
            String content = rawChunks.get(i).trim();
            if (content.length() < 20) continue; // 过短跳过

            // 检测内容类型
            String contentType = detectContentType(content);

            TextChunk chunk = new TextChunk();
            chunk.setContent(content);
            chunk.setChunkIndex(i);
            chunk.setKnowledgeBaseId(knowledgeBaseId);
            chunk.setCategory(category);
            chunk.setContentType(contentType);
            chunks.add(chunk);
        }

        log.info("文本切分完成: 原始段落={}，有效切片={}", paragraphs.length, chunks.size());
        return chunks;
    }

    /**
     * 合并短段落 / 切分超长段落
     */
    private List<String> mergeParagraphs(String[] paragraphs) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;

            if (buffer.length() + para.length() <= TARGET_CHUNK_SIZE) {
                // 可以继续合并
                if (!buffer.isEmpty()) buffer.append("\n\n");
                buffer.append(para);
            } else {
                // buffer 已足够，先保存
                if (!buffer.isEmpty()) {
                    result.add(buffer.toString());
                    buffer.setLength(0);
                }

                if (para.length() <= MAX_CHUNK_SIZE) {
                    // 段落本身不超长
                    buffer.append(para);
                } else {
                    // 超长段落：按句子切分
                    List<String> sentenceChunks = splitBySentence(para);
                    result.addAll(sentenceChunks);
                }
            }
        }

        if (!buffer.isEmpty()) {
            result.add(buffer.toString());
        }
        return result;
    }

    /**
     * 按句子切分超长段落（句末标点：。！？；\n）
     */
    private List<String> splitBySentence(String text) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？；\\.!?;\\n])");
        StringBuilder buffer = new StringBuilder();

        for (String sentence : sentences) {
            if (buffer.length() + sentence.length() > MAX_CHUNK_SIZE && !buffer.isEmpty()) {
                chunks.add(buffer.toString().trim());
                // 保留重叠部分
                String overlap = buffer.length() > OVERLAP_SIZE
                        ? buffer.substring(buffer.length() - OVERLAP_SIZE)
                        : buffer.toString();
                buffer = new StringBuilder(overlap);
            }
            buffer.append(sentence);
        }
        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString().trim());
        }
        return chunks;
    }

    /**
     * 自动检测内容类型（用于 Milvus metadata）
     */
    private String detectContentType(String content) {
        if (content.contains("步骤") || content.contains("流程") || content.contains("方法") || content.contains("操作")) {
            return "procedure";
        } else if (content.contains("注意") || content.contains("风险") || content.contains("禁止") || content.contains("警告")) {
            return "warning";
        } else if (content.contains("示例") || content.contains("案例") || content.contains("例如")) {
            return "example";
        } else if (content.contains("定义") || content.contains("概念") || content.contains("是指")) {
            return "definition";
        }
        return "general";
    }

    /**
     * 文本切片数据结构
     */
    public static class TextChunk {
        private String content;
        private int chunkIndex;
        private Long knowledgeBaseId;
        private String category;
        private String contentType;
        private int pageNumber;
        private String chapter;

        // 音视频专用 · 时间戳级溯源
        private Long startMs;       // 起始毫秒
        private Long endMs;         // 结束毫秒
        private String speakerId;   // 说话人 ID
        private String sourceObjectName;  // 原始文件在 MinIO/OSS 的对象名

        // Getters & Setters
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public int getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
        public Long getKnowledgeBaseId() { return knowledgeBaseId; }
        public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        public String getChapter() { return chapter; }
        public void setChapter(String chapter) { this.chapter = chapter; }
        public Long getStartMs() { return startMs; }
        public void setStartMs(Long startMs) { this.startMs = startMs; }
        public Long getEndMs() { return endMs; }
        public void setEndMs(Long endMs) { this.endMs = endMs; }
        public String getSpeakerId() { return speakerId; }
        public void setSpeakerId(String speakerId) { this.speakerId = speakerId; }
        public String getSourceObjectName() { return sourceObjectName; }
        public void setSourceObjectName(String sourceObjectName) { this.sourceObjectName = sourceObjectName; }
    }
}
