package com.simon.MindCrew.service.impl;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.DocumentClassifierService;
import com.simon.MindCrew.service.knowledge.AudioTranscriber;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.service.knowledge.MilvusService;
import com.simon.MindCrew.service.knowledge.TextChunker;
import com.simon.MindCrew.service.knowledge.VideoProcessor;
import com.simon.MindCrew.service.knowledge.WechatChatParser;
import org.springframework.ai.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档异步处理任务（独立组件，避免 @Async 自调用 AOP 代理失效问题）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessTask {

    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper kbChunkMapper;
    private final MilvusService milvusService;
    private final DocumentExtractor documentExtractor;
    private final TextChunker textChunker;
    private final EmbeddingModel embeddingModel;
    private final FileStorageService fileStorage;
    private final WechatChatParser wechatChatParser;
    private final VideoProcessor videoProcessor;
    private final DocumentClassifierService classifier;
    private final com.simon.MindCrew.service.knowledge.TextNoiseCleaner textNoiseCleaner;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** 向量库未启用时，跳过向量化与 Milvus 写入，仅落关键词可检索的切片 */
    @Value("${milvus.enabled:true}")
    private boolean milvusEnabled;

    // 阿里云 text-embedding-v3 单批上限为 10
    private static final int EMBED_BATCH_SIZE = 10;

    /**
     * 异步文档处理流水线：uploading → processing → ready (or failed)
     */
    @Async
    public void process(Long knowledgeBaseId) {
        MedKnowledgeBase kb = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (kb == null) {
            log.error("文档处理启动失败：找不到记录 id={}，可能事务未提交或已被删除", knowledgeBaseId);
            return;
        }

        try {
            // 更新状态为 processing
            kb.setStatus("processing");
            kb.setErrorMsg(null);
            knowledgeBaseMapper.updateById(kb);
            deleteStoredChunks(knowledgeBaseId);

            // 1. 从本地磁盘读取文件（fileUrl 为相对路径，如 knowledge/uuid.pdf）
            Path filePath = Paths.get(uploadPath, kb.getFileUrl());
            if (!Files.exists(filePath)) {
                throw new RuntimeException("本地文件不存在: " + filePath.toAbsolutePath());
            }

            String fileType = kb.getFileType().toLowerCase();
            boolean isAudio = AudioTranscriber.supportedExtensions().contains(fileType);
            boolean isVideo = VideoProcessor.supportedExtensions().contains(fileType);
            boolean isWechat = isWechatChat(kb, filePath, fileType);

            List<TextChunker.TextChunk> chunks;

            if (isAudio) {
                // ── 音频分支：上传 MinIO → 获取预签名 URL → 调 ASR → 每句话一个 chunk ──
                chunks = processAudio(kb, filePath, knowledgeBaseId);
            } else if (isVideo) {
                // ── 视频分支：FFmpeg 抽音轨+关键帧 → ASR+VL → 多类 chunk 混合入库 ──
                chunks = processVideo(kb, filePath, knowledgeBaseId);
            } else if (isWechat) {
                // ── 微信聊天分支：按 session 切分，每个会话一个 chunk ──
                chunks = processWechatChat(kb, filePath, knowledgeBaseId);
            } else {
                // ── 文档分支（PDF/Word/PPT/Excel/图片/...）─────────────────────────
                InputStream inputStream = Files.newInputStream(filePath);
                String text = documentExtractor.extract(inputStream, fileType);
                if (StringUtils.isBlank(text)) {
                    throw new RuntimeException("文档内容为空，请检查文件");
                }
                // 招聘网站等 PDF 常带隐藏水印（随机字母数字串），简历类文档入库前清洗
                String category = kb.getCategory() == null ? "" : kb.getCategory().toLowerCase();
                if (category.contains("简历") || category.contains("resume") || category.contains("hr")
                        || category.contains("招聘") || category.contains("recruit")) {
                    text = textNoiseCleaner.cleanResume(text);
                }
                chunks = textChunker.chunk(text, knowledgeBaseId, kb.getCategory());
            }

            if (chunks.isEmpty()) {
                throw new RuntimeException("文档切片结果为空");
            }

            // 4. 同步写入 kb_chunk，供中文关键词/BM25 检索使用
            persistChunks(chunks);

            // 5/6. 向量化 + 写入 Milvus（未启用向量库时跳过，关键词检索仍可用）
            if (milvusEnabled) {
                List<List<Float>> embeddings = batchEmbed(chunks);
                milvusService.insertVectors(chunks, embeddings);
            } else {
                log.info("[Process] milvus.enabled=false，跳过向量化与 Milvus 写入: id={}, chunks={}",
                        knowledgeBaseId, chunks.size());
            }

            // 7. 更新状态为 ready
            kb.setChunkCount(chunks.size());
            kb.setStatus("ready");
            kb.setErrorMsg(null);
            knowledgeBaseMapper.updateById(kb);

            log.info("文档处理完成: id={}, chunks={}", knowledgeBaseId, chunks.size());

            // 8. AI 自动分类（异步执行 · 失败不影响入库结果）
            //    取前若干 chunk 内容作为分类样本，避免视频/音频拿不到原始文本
            try {
                StringBuilder sample = new StringBuilder();
                for (TextChunker.TextChunk c : chunks) {
                    if (c.getContent() != null) sample.append(c.getContent()).append('\n');
                    if (sample.length() > 5000) break;
                }
                classifier.classify(knowledgeBaseId, kb.getName(), 0, sample.toString());
            } catch (Exception ce) {
                log.warn("文档 AI 分类失败（不影响入库）: id={} err={}", knowledgeBaseId, ce.getMessage());
            }

        } catch (Exception e) {
            log.error("文档处理失败: id={}", knowledgeBaseId, e);
            deleteStoredChunks(knowledgeBaseId);
            kb.setStatus("failed");
            kb.setErrorMsg(e.getMessage());
            knowledgeBaseMapper.updateById(kb);
        }
    }

    /**
     * 分批向量化，避免单次请求超限
     */
    private List<List<Float>> batchEmbed(List<TextChunker.TextChunk> chunks) {
        List<List<Float>> allEmbeddings = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
            int end = Math.min(i + EMBED_BATCH_SIZE, chunks.size());
            List<TextChunker.TextChunk> batch = chunks.subList(i, end);

            // Spring AI 1.0.0: EmbeddingModel.embed(String) 返回 float[]
            for (TextChunker.TextChunk chunk : batch) {
                float[] raw = embeddingModel.embed(chunk.getContent());
                List<Float> floats = new java.util.ArrayList<>(raw.length);
                for (float f : raw) floats.add(f);
                allEmbeddings.add(floats);
            }

            log.debug("向量化进度: {}/{}", end, chunks.size());
        }

        return allEmbeddings;
    }

    private void persistChunks(List<TextChunker.TextChunk> chunks) {
        for (TextChunker.TextChunk chunk : chunks) {
            KbChunk entity = new KbChunk();
            entity.setKbId(chunk.getKnowledgeBaseId());
            entity.setContent(chunk.getContent());
            entity.setChunkIndex(chunk.getChunkIndex());

            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("contentType", chunk.getContentType() != null ? chunk.getContentType() : "");
            meta.put("pageNumber", chunk.getPageNumber());
            meta.put("chapter", chunk.getChapter() != null ? chunk.getChapter() : "");
            // 音视频时间戳元数据（非音频则不写入）
            if (chunk.getStartMs() != null) meta.put("startMs", chunk.getStartMs());
            if (chunk.getEndMs() != null) meta.put("endMs", chunk.getEndMs());
            if (chunk.getSpeakerId() != null) meta.put("speakerId", chunk.getSpeakerId());
            if (chunk.getSourceObjectName() != null) meta.put("sourceObjectName", chunk.getSourceObjectName());

            entity.setMetadata(JSON.toJSONString(meta));
            entity.setVectorId(null);
            kbChunkMapper.insert(entity);
        }
        log.info("kb_chunk 写入成功: {}条", chunks.size());
    }

    /**
     * 音频处理：上传 MinIO → ASR 转写 → 每句话一个 chunk（带时间戳）。
     */
    private List<TextChunker.TextChunk> processAudio(MedKnowledgeBase kb, Path filePath, Long kbId) {
        String fileType = kb.getFileType().toLowerCase();

        // 1. 上传到 MinIO 拿预签名 URL（DashScope 需要公网访问）
        String contentType = switch (fileType) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a", "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "opus" -> "audio/opus";
            case "ogg" -> "audio/ogg";
            case "amr" -> "audio/amr";
            default -> "application/octet-stream";
        };
        String objectName = fileStorage.uploadLocalFile(filePath, "asr-audio", contentType);
        String publicUrl;
        try {
            publicUrl = fileStorage.getFileUrl(objectName);
        } catch (Exception e) {
            throw new RuntimeException("获取预签名 URL 失败 (" + fileStorage.type() + "): " + e.getMessage(), e);
        }

        try {
            // 2. 调 ASR 拿带时间戳的逐句转写
            log.info("[Audio] 开始 ASR 转写: kbId={} url={}", kbId, publicUrl);
            AudioTranscriber.TranscriptionResult result = documentExtractor.transcribeAudio(publicUrl);
            if (!result.success()) {
                throw new RuntimeException("ASR 失败: " + result.errorMsg());
            }
            log.info("[Audio] 转写完成: {} 句子, 总时长 {}ms", result.sentences().size(), result.totalDurationMs());

            // 3. 每句话转成一个 chunk（含时间戳 + 远程对象名供前端播放）
            List<TextChunker.TextChunk> chunks = new ArrayList<>();
            int idx = 0;
            for (AudioTranscriber.Sentence s : result.sentences()) {
                if (StringUtils.isBlank(s.text())) continue;
                TextChunker.TextChunk c = new TextChunker.TextChunk();
                c.setKnowledgeBaseId(kbId);
                c.setCategory(kb.getCategory());
                c.setContent(s.text());
                c.setChunkIndex(idx++);
                c.setContentType("audio");
                c.setPageNumber(s.index());
                c.setStartMs(s.startMs());
                c.setEndMs(s.endMs());
                c.setSpeakerId(s.speakerId());
                c.setSourceObjectName(objectName);  // 同一文件所有 chunk 共享对象名
                chunks.add(c);
            }
            return chunks;
        } catch (Exception e) {
            // 失败时清理远程文件（避免泄漏）
            fileStorage.deleteObject(objectName);
            throw e;
        }
    }

    private void deleteStoredChunks(Long knowledgeBaseId) {
        kbChunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, knowledgeBaseId));
    }

    // ─────────────────────────────────────────────
    // 视频处理
    // ─────────────────────────────────────────────
    /**
     * 视频处理：
     *   1. 上传原视频到 storage（前端要播放）
     *   2. 本地 FFmpeg 抽音轨 + 关键帧
     *   3. 音轨临时上传 storage 走 ASR；关键帧走 VL
     *   4. 音频句子 + 关键帧描述都作为 chunk，全部带 startMs/endMs
     */
    private List<TextChunker.TextChunk> processVideo(MedKnowledgeBase kb, Path filePath, Long kbId) {
        String fileType = kb.getFileType().toLowerCase();
        String contentType = switch (fileType) {
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "flv" -> "video/x-flv";
            case "webm" -> "video/webm";
            default -> "video/mp4";
        };

        // 1. 视频本体上传 storage（前端要点击播放）
        String videoObjectName = fileStorage.uploadLocalFile(filePath, "video", contentType);

        try {
            // 2. 走 FFmpeg + ASR + VL
            VideoProcessor.VideoParseResult result = videoProcessor.process(filePath, videoObjectName);
            if (!result.success()) {
                throw new RuntimeException("视频处理失败: " + result.errorMsg()
                        + "  · 诊断: " + result.diagnosticsText());
            }
            int totalSegs = result.audioSegments().size() + result.keyframes().size();
            if (totalSegs == 0) {
                // 不再写兜底元数据 chunk · 失败必须明示
                throw new RuntimeException("视频处理后无可用内容 · 详细原因: "
                        + result.diagnosticsText());
            }

            // 3. 组装 chunks
            List<TextChunker.TextChunk> chunks = new ArrayList<>();
            int idx = 0;

            // 3.1 音频句子
            for (VideoProcessor.AudioSegment a : result.audioSegments()) {
                TextChunker.TextChunk c = new TextChunker.TextChunk();
                c.setKnowledgeBaseId(kbId);
                c.setCategory(kb.getCategory());
                c.setContent(a.text());
                c.setChunkIndex(idx++);
                c.setContentType("video");    // 走音视频统一 mediaType=video
                c.setPageNumber(a.index());
                c.setStartMs(a.startMs());
                c.setEndMs(a.endMs());
                c.setSpeakerId(a.speakerId());
                c.setSourceObjectName(videoObjectName);
                chunks.add(c);
            }

            // 3.2 关键帧描述
            for (VideoProcessor.KeyframeSegment k : result.keyframes()) {
                String text = k.toIndexedText();
                if (text == null || text.isBlank()) continue;
                TextChunker.TextChunk c = new TextChunker.TextChunk();
                c.setKnowledgeBaseId(kbId);
                c.setCategory(kb.getCategory());
                c.setContent(text);
                c.setChunkIndex(idx++);
                c.setContentType("video");
                c.setPageNumber(10000 + k.index());  // 跟音频段错开
                c.setStartMs(k.timeMs());
                c.setEndMs(k.timeMs() + (long) 30 * 1000);  // 关键帧默认覆盖窗口 30 秒
                c.setSourceObjectName(videoObjectName);
                chunks.add(c);
            }

            log.info("[Video] 入库 {} chunks（音频 {} 句 + 关键帧 {} 个）",
                    chunks.size(), result.audioSegments().size(), result.keyframes().size());
            return chunks;
        } catch (Exception e) {
            // 失败时清理已上传的视频文件
            try { fileStorage.deleteObject(videoObjectName); } catch (Exception ignored) {}
            throw e;
        }
    }

    // ─────────────────────────────────────────────
    // 微信聊天记录处理
    // ─────────────────────────────────────────────

    /**
     * 嗅探是否是微信聊天记录。两种判断：
     *  1. 分类标签包含 "微信" / "wechat" / "chat" 等
     *  2. 文件名包含 "wechat" / "微信" / "聊天" 关键词
     *  仅对 txt / csv / html 文件触发。
     *
     * 注意：HTML 文件如果没有分类/文件名提示，会走标准 HTML 正文提取（网页文章）。
     */
    private boolean isWechatChat(MedKnowledgeBase kb, Path filePath, String fileType) {
        if (!WechatChatParser.supportedExtensions().contains(fileType)) return false;

        String category = kb.getCategory();
        if (category != null) {
            String c = category.toLowerCase();
            if (c.contains("微信") || c.contains("wechat") || c.contains("chat") || c.contains("聊天")) return true;
        }
        String filename = filePath.getFileName().toString().toLowerCase();
        return filename.contains("wechat") || filename.contains("微信") || filename.contains("聊天");
    }

    /**
     * 微信聊天专用处理：解析 → 按 session 切片 → 每个 session 一个 chunk，
     * 元数据含 source_type=wechat / participants / session_start / session_end。
     */
    private List<TextChunker.TextChunk> processWechatChat(MedKnowledgeBase kb, Path filePath, Long kbId) {
        WechatChatParser.ParseResult result;
        try (InputStream in = Files.newInputStream(filePath)) {
            result = wechatChatParser.parse(in);
        } catch (Exception e) {
            throw new RuntimeException("微信聊天解析失败: " + e.getMessage(), e);
        }
        if (result.sessions().isEmpty()) {
            throw new RuntimeException("微信聊天解析后无可用会话（请检查文件格式）");
        }

        List<TextChunker.TextChunk> chunks = new ArrayList<>();
        int idx = 0;
        for (WechatChatParser.Session s : result.sessions()) {
            String text = wechatChatParser.renderSession(s);
            TextChunker.TextChunk c = new TextChunker.TextChunk();
            c.setKnowledgeBaseId(kbId);
            c.setCategory(kb.getCategory());
            c.setContent(text);
            c.setChunkIndex(idx++);
            c.setContentType("wechat");
            c.setPageNumber(s.index());
            // session 起始时间作为时间戳（毫秒），便于后续按时间检索
            c.setStartMs(s.startTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            c.setEndMs(s.endTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            // 把参与者拼成 "spk1+spk2+..." 形式存在 speakerId 字段
            c.setSpeakerId(String.join(",", s.participants()));
            chunks.add(c);
        }
        log.info("[Wechat] 入库 {} 个会话 chunk（{} 条消息，参与者 {}）",
                chunks.size(), result.totalMessages(), result.allParticipants());
        return chunks;
    }
}
