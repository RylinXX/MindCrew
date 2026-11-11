package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.MedConversation;
import com.simon.MindCrew.entity.MedMessage;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.dto.ChatRequestDTO;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.mapper.MedConversationMapper;
import com.simon.MindCrew.mapper.MedMessageMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.rag.RagPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话接口
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final RagPipeline ragPipeline;
    private final UserService userService;
    private final MedConversationMapper conversationMapper;
    private final MedMessageMapper messageMapper;
    private final QaMessageMapper qaMessageMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 发送消息（SSE 流式返回）
     * GET /api/chat/stream?conversationId=xxx&message=xxx
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) Long conversationId,
            @RequestParam String message) {

        SseEmitter emitter = new SseEmitter(120_000L); // 120秒超时

        Long userId = userService.getCurrentUserId();

        executor.execute(() -> {
            ragPipeline.execute(userId, conversationId, message, null, emitter);
        });

        return emitter;
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/conversations")
    public Result<PageVO<MedConversation>> listConversations(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {

        Long userId = userService.getCurrentUserId();
        Page<MedConversation> page = new Page<>(current, size);
        conversationMapper.selectPage(page, new LambdaQueryWrapper<MedConversation>()
                .eq(MedConversation::getUserId, userId)
                .eq(MedConversation::getDeleted, 0)
                .orderByDesc(MedConversation::getLastActive));

        return Result.success(PageVO.of(page));
    }

    /**
     * 获取会话消息历史
     */
    @GetMapping("/history/{conversationId}")
    public Result<PageVO<MedMessage>> getHistory(
            @PathVariable Long conversationId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "50") Integer size) {

        Long userId = userService.getCurrentUserId();
        // 验证会话属于当前用户
        MedConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("会话不存在");
        }

        Page<MedMessage> page = new Page<>(current, size);
        messageMapper.selectPage(page, new LambdaQueryWrapper<MedMessage>()
                .eq(MedMessage::getConversationId, conversationId)
                .orderByAsc(MedMessage::getCreateTime));

        return Result.success(PageVO.of(page));
    }

    /**
     * 获取检索过程详情（答辩演示专用）
     */
    @GetMapping("/retrieval-log/{messageId}")
    public Result<Object> getRetrievalLog(@PathVariable Long messageId) {
        // MedMessage.retrievalLog 是 @TableField(exist=false)，从同表的 QaMessage 实体读取真实列
        QaMessage message = qaMessageMapper.selectById(messageId);
        if (message == null) {
            return Result.error("消息不存在");
        }
        String log = message.getRetrievalLog();
        if (log == null) return Result.success(null);
        return Result.success(com.alibaba.fastjson2.JSON.parse(log));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable Long conversationId) {
        Long userId = userService.getCurrentUserId();
        MedConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("会话不存在");
        }
        conversationMapper.deleteById(conversationId);
        return Result.success();
    }

    /**
     * 提交消息反馈
     */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestBody ChatRequestDTO.FeedbackDTO dto) {
        MedMessage message = messageMapper.selectById(dto.getMessageId());
        if (message == null) return Result.error("消息不存在");
        message.setFeedback(dto.getRating());
        messageMapper.updateById(message);
        return Result.success();
    }

    /**
     * 导出会话为 Markdown 文件
     * GET /api/chat/export/{conversationId}
     */
    @GetMapping("/export/{conversationId}")
    public ResponseEntity<byte[]> exportMarkdown(@PathVariable Long conversationId) {
        Long userId = userService.getCurrentUserId();
        MedConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }

        List<MedMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<MedMessage>()
                        .eq(MedMessage::getConversationId, conversationId)
                        .orderByAsc(MedMessage::getCreateTime));

        String markdown = buildMarkdown(conv, messages);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        String filename = "mindcrew-" + conversationId + ".md";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("text/markdown; charset=UTF-8"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());

        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // ==================== 私有方法 ====================

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String buildMarkdown(MedConversation conv, List<MedMessage> messages) {
        StringBuilder md = new StringBuilder();
        String exportTime = LocalDateTime.now().format(DT_FMT);
        String createTime = conv.getCreateTime() != null ? conv.getCreateTime().format(DT_FMT) : "-";

        // ===== 文件头 =====
        md.append("# ").append(conv.getTitle()).append("\n\n");
        md.append("> **平台**: MindCrew 通用知识问答  \n");
        md.append("> **创建时间**: ").append(createTime).append("  \n");
        md.append("> **导出时间**: ").append(exportTime).append("  \n");
        md.append("> **消息数量**: ").append(messages.size()).append("  \n\n");
        md.append("---\n\n");

        // ===== 对话内容 =====
        for (MedMessage msg : messages) {
            String time = msg.getCreateTime() != null ? msg.getCreateTime().format(DT_FMT) : "";
            if ("user".equals(msg.getRole())) {
                md.append("### 👤 用户");
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                md.append("\n\n");
                md.append(msg.getContent()).append("\n\n");
            } else {
                md.append("### 🤖 MindCrew");
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                if (msg.getResponseTime() != null) {
                    md.append(" · ⏱ ").append(msg.getResponseTime()).append("ms");
                }
                md.append("\n\n");
                md.append(msg.getContent()).append("\n\n");

                // 来源引用
                appendSources(md, msg.getSources());

                // 反馈标记
                if (msg.getFeedback() != null && msg.getFeedback() == 1) {
                    md.append("> 👍 用户认为此回答有用\n\n");
                } else if (msg.getFeedback() != null && msg.getFeedback() == -1) {
                    md.append("> 👎 用户认为此回答无用\n\n");
                }
            }
            md.append("---\n\n");
        }

        // ===== 文件尾 =====
        md.append("*本文档由 MindCrew 自动生成，仅供参考。*\n");
        return md.toString();
    }

    private void appendSources(StringBuilder md, String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) return;
        try {
            JSONArray sources = JSON.parseArray(sourcesJson);
            if (sources == null || sources.isEmpty()) return;
            md.append("**参考来源**\n\n");
            for (int i = 0; i < sources.size(); i++) {
                JSONObject s = sources.getJSONObject(i);
                String name = s.getString("name");
                String chapter = s.getString("chapter");
                Integer page = s.getInteger("pageNumber");
                md.append(i + 1).append(". 《").append(name != null ? name : "知识库文档").append("》");
                if (chapter != null && !chapter.isBlank()) md.append(" · ").append(chapter);
                if (page != null && page > 0) md.append(" · 第 ").append(page).append(" 页");
                md.append("\n");
            }
            md.append("\n");
        } catch (Exception ignored) {
        }
    }
}
