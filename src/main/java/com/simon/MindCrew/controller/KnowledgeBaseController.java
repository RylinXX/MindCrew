package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.service.KnowledgeBaseService;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final UserService userService;
    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper kbChunkMapper;

    /**
     * 上传文档
     */
    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("category") String category,
            @RequestParam(value = "description", required = false) String description) {
        Long id = knowledgeBaseService.uploadDocument(
                file,
                file.getOriginalFilename(),
                category,
                description,
                userService.getCurrentUserId());
        return Result.success("上传成功，正在后台处理...", id);
    }

    /**
     * 分页查询知识库列表
     */
    @GetMapping("/list")
    public Result<PageVO<MedKnowledgeBase>> list(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") Integer size,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) String status) {
        Page<MedKnowledgeBase> page = knowledgeBaseService.listKnowledge(current, size, category, status);
        return Result.success(PageVO.of(page));
    }

    /**
     * 知识库汇总统计（全量，不随分页变化）
     * GET /api/knowledge/stats?category=xxx
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(
            @RequestParam(value = "category", required = false) String category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", countKb(category, null));
        body.put("ready", countKb(category, "ready"));
        body.put("processing", countKb(category, "processing") + countKb(category, "uploading"));
        body.put("failed", countKb(category, "failed"));
        body.put("chunks", kbChunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()));
        return Result.success(body);
    }

    private long countKb(String category, String status) {
        return knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<MedKnowledgeBase>()
                .eq(category != null && !category.isBlank(), MedKnowledgeBase::getCategory, category)
                .eq(status != null && !status.isBlank(), MedKnowledgeBase::getStatus, status));
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/{id}")
    public Result<MedKnowledgeBase> getById(@PathVariable Long id) {
        return Result.success(knowledgeBaseService.getById(id));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.deleteById(id);
        return Result.success();
    }

    /**
     * 重新处理（处理失败时）
     */
    @PostMapping("/{id}/reprocess")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> reprocess(@PathVariable Long id) {
        knowledgeBaseService.reprocess(id);
        return Result.success("已重新提交处理");
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        return Result.success(knowledgeBaseService.listCategories());
    }

    /**
     * 任务 7 · 切换 KB 可见性 (public / scoped / private)
     */
    @PutMapping("/{id}/visibility")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateVisibility(@PathVariable Long id, @RequestParam("visibility") String visibility) {
        knowledgeBaseService.updateVisibility(id, visibility);
        return Result.success();
    }
}
