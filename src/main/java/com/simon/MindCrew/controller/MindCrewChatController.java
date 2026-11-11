package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.support.KbIdsParser;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MindCrew v2 对话控制器（使用 QaConversation / QaMessage 新实体）
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/chat")
@RequiredArgsConstructor
public class MindCrewChatController {

    private final MindCrewAgent docMindAgent;
    private final UserService userService;
    private final QaConversationMapper qaConversationMapper;
    private final QaMessageMapper qaMessageMapper;
    private final FileStorageService fileStorage;

    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;   // 10MB

    // 用 DelegatingSecurityContextExecutorService 包装，确保 SecurityContext 传递到异步线程
    private final ExecutorService executor =
            new DelegatingSecurityContextExecutorService(Executors.newCachedThreadPool());

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== SSE 流式问答 ====================

    /**
     * SSE 流式问答
     * GET /api/v2/chat/stream?conversationId=xxx&message=xxx&kbIds=1,2,3
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) Long conversationId,
            @RequestParam String message,
            @RequestParam(required = false) String kbIds,
            @RequestParam(required = false) String imageObjectNames) {

        final List<Long> parsedKbIds;
        try {
            parsedKbIds = KbIdsParser.parse(kbIds);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), e.getMessage());
        }

        // 解析图片对象名列表（逗号分隔）
        final List<String> parsedImages;
        if (imageObjectNames == null || imageObjectNames.isBlank()) {
            parsedImages = List.of();
        } else {
            parsedImages = java.util.Arrays.stream(imageObjectNames.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).toList();
            if (parsedImages.size() > 4) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "单次最多 4 张图片");
            }
        }

        SseEmitter emitter = new SseEmitter(180_000L);
        Long userId = userService.getCurrentUserId();
        String userIdStr = String.valueOf(userId);

        executor.execute(() -> {
            try {
                docMindAgent.execute(userIdStr, conversationId, message, parsedKbIds, parsedImages, emitter);
            } catch (Exception e) {
                log.error("[MindCrewChatController] stream异常", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ==================== 图片上传 · 任务 10 ====================

    /**
     * 上传一张图片到对象存储，返回 objectName 给前端。
     * 前端发送 SSE 时把 objectName 带在 imageObjectNames 参数里。
     */
    @PostMapping("/upload-image")
    public Result<java.util.Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "图片为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "图片过大（" + (file.getSize() / 1024 / 1024) + "MB），最大 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "仅支持图片格式（image/*），收到: " + contentType);
        }

        // 真实上传到 OSS/MinIO，不 mock
        String objectName = fileStorage.uploadFile(file, "chat-image");
        String url        = fileStorage.getFileUrl(objectName);

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("objectName", objectName);
        out.put("url",        url);
        out.put("sizeBytes",  file.getSize());
        out.put("mimeType",   contentType);
        out.put("originalName", file.getOriginalFilename());
        return Result.success(out);
    }

    // ==================== 会话管理 ====================

    /**
     * 分页获取当前用户的会话列表
     * GET /api/v2/chat/conversations?current=1&size=20
     */
    @GetMapping("/conversations")
    public Result<PageVO<QaConversation>> listConversations(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {

        Long userId = userService.getCurrentUserId();
        Page<QaConversation> page = new Page<>(current, size);
        qaConversationMapper.selectPage(page, new LambdaQueryWrapper<QaConversation>()
                .eq(QaConversation::getUserId, userId)
                .eq(QaConversation::getDeleted, 0)
                .orderByDesc(QaConversation::getLastActive));

        return Result.success(PageVO.of(page));
    }

    /**
     * 获取会话消息历史
     * GET /api/v2/chat/history/{conversationId}?current=1&size=50
     */
    @GetMapping("/history/{conversationId}")
    public Result<PageVO<QaMessage>> getHistory(
            @PathVariable Long conversationId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "50") Integer size) {

        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("会话不存在或无权访问");
        }

        Page<QaMessage> page = new Page<>(current, size);
        qaMessageMapper.selectPage(page, new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conversationId)
                .orderByAsc(QaMessage::getCreateTime));

        return Result.success(PageVO.of(page));
    }

    /**
     * 删除会话
     * DELETE /api/v2/chat/conversations/{conversationId}
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable Long conversationId) {
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("会话不存在或无权访问");
        }
        qaConversationMapper.deleteById(conversationId);
        return Result.success();
    }

    // ==================== 消息反馈 ====================

    /**
     * 提交消息反馈
     * POST /api/v2/chat/feedback  body: {messageId, rating}
     */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestBody FeedbackDTO dto) {
        QaMessage message = qaMessageMapper.selectById(dto.getMessageId());
        if (message == null) {
            return Result.error("消息不存在");
        }
        // 验证消息归属（通过会话）
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(message.getConversationId());
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("无权操作此消息");
        }
        message.setFeedback(dto.getRating());
        qaMessageMapper.updateById(message);
        return Result.success();
    }

    // ==================== 导出 ====================

    /**
     * 导出会话为 Markdown 文件
     * GET /api/v2/chat/export/{conversationId}
     */
    @GetMapping("/export/{conversationId}")
    public ResponseEntity<byte[]> exportMarkdown(@PathVariable Long conversationId) {
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }

        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getConversationId, conversationId)
                        .orderByAsc(QaMessage::getCreateTime));

        String markdown = buildMarkdown(conv, messages);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        String filename = "mindcrew-" + conversationId + ".md";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("text/markdown; charset=UTF-8"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());

        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // ==================== Agent 推理链 ====================

    /**
     * 获取消息的 Agent 推理链（agentTrace 字段）
     * GET /api/v2/chat/agent-trace/{messageId}
     */
    @GetMapping("/agent-trace/{messageId}")
    public Result<Object> getAgentTrace(@PathVariable Long messageId) {
        QaMessage message = qaMessageMapper.selectById(messageId);
        if (message == null) {
            return Result.error("消息不存在");
        }
        // 验证消息归属
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(message.getConversationId());
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("无权访问此消息");
        }

        String agentTrace = message.getAgentTrace();
        if (agentTrace == null || agentTrace.isBlank()) {
            return Result.success(null);
        }
        try {
            return Result.success(JSON.parse(agentTrace));
        } catch (Exception e) {
            return Result.success(agentTrace);
        }
    }

    // ==================== Markdown 构建 ====================

    private String buildMarkdown(QaConversation conv, List<QaMessage> messages) {
        StringBuilder md = new StringBuilder();
        String exportTime = LocalDateTime.now().format(DT_FMT);
        String createTime = conv.getCreateTime() != null ? conv.getCreateTime().format(DT_FMT) : "-";
        String title = conv.getTitle() != null ? conv.getTitle() : "MindCrew 对话";

        md.append("# ").append(title).append("\n\n");
        md.append("> **平台**: MindCrew 智能文档问答  \n");
        md.append("> **创建时间**: ").append(createTime).append("  \n");
        md.append("> **导出时间**: ").append(exportTime).append("  \n");
        md.append("> **消息数量**: ").append(messages.size()).append("  \n\n");
        md.append("---\n\n");

        for (QaMessage msg : messages) {
            String time = msg.getCreateTime() != null ? msg.getCreateTime().format(DT_FMT) : "";
            if ("user".equals(msg.getRole())) {
                md.append("### 用户");
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                md.append("\n\n");
                md.append(msg.getContent()).append("\n\n");
            } else {
                md.append("### MindCrew");
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                if (msg.getResponseTime() != null) {
                    md.append(" · ").append(msg.getResponseTime()).append("ms");
                }
                md.append("\n\n");
                md.append(msg.getContent()).append("\n\n");

                // 来源引用
                appendSources(md, msg.getSources());

                // Agent 推理链
                appendAgentTrace(md, msg.getAgentTrace());

                // 反馈标记
                if (msg.getFeedback() != null && msg.getFeedback() == 1) {
                    md.append("> 用户认为此回答有用\n\n");
                } else if (msg.getFeedback() != null && msg.getFeedback() == -1) {
                    md.append("> 用户认为此回答无用\n\n");
                }
            }
            md.append("---\n\n");
        }

        md.append("*本文档由 MindCrew 自动生成。*\n");
        return md.toString();
    }

    private void appendSources(StringBuilder md, String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) return;
        try {
            JSONArray sources = JSON.parseArray(sourcesJson);
            if (sources == null || sources.isEmpty()) return;
            md.append("**参考来源**\n\n");
            for (int i = 0; i < sources.size(); i++) {
                com.alibaba.fastjson2.JSONObject s = sources.getJSONObject(i);
                String name = s.getString("name");
                String chapter = s.getString("chapter");
                Integer page = s.getInteger("pageNumber");
                md.append(i + 1).append(". 《").append(name != null ? name : "文档").append("》");
                if (chapter != null && !chapter.isBlank()) md.append(" · ").append(chapter);
                if (page != null && page > 0) md.append(" · 第 ").append(page).append(" 页");
                md.append("\n");
            }
            md.append("\n");
        } catch (Exception ignored) {
        }
    }

    private void appendAgentTrace(StringBuilder md, String agentTraceJson) {
        if (agentTraceJson == null || agentTraceJson.isBlank()) return;
        try {
            JSONArray trace = JSON.parseArray(agentTraceJson);
            if (trace == null || trace.isEmpty()) return;
            md.append("<details>\n<summary>Agent 推理链</summary>\n\n");
            for (int i = 0; i < trace.size(); i++) {
                com.alibaba.fastjson2.JSONObject step = trace.getJSONObject(i);
                md.append("**Step ").append(step.getIntValue("step")).append("**  \n");
                md.append("- Thought: ").append(step.getString("thought")).append("  \n");
                md.append("- Action: ").append(step.getString("action")).append("  \n");
                String obs = step.getString("observation");
                if (obs != null && !obs.isBlank()) {
                    md.append("- Observation: ").append(obs).append("  \n");
                }
                md.append("\n");
            }
            md.append("</details>\n\n");
        } catch (Exception ignored) {
        }
    }

    // ==================== DTO ====================

    @lombok.Data
    public static class FeedbackDTO {
        private Long messageId;
        /** 1: 有用, -1: 无用, 0: 取消 */
        private Integer rating;
    }
}
