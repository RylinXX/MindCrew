package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.KbCategory;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.service.DocumentClassifierService;
import com.simon.MindCrew.service.KbCategoryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分类字典 + 文档分类操作 API
 *
 *   GET    /api/v2/kb-category/list                              列字典（公开）
 *   POST   /api/v2/kb-category                                   新增（admin）
 *   PUT    /api/v2/kb-category/{id}                              编辑（admin）
 *   DELETE /api/v2/kb-category/{id}                              删除（admin）
 *
 *   PUT    /api/v2/kb-category/document/{kbId}/category          用户改某文档分类 → 锁定
 *   POST   /api/v2/kb-category/document/{kbId}/reclassify        重跑 LLM 分类（admin）
 */
@RestController
@RequestMapping("/api/v2/kb-category")
@RequiredArgsConstructor
public class KbCategoryController {

    private final KbCategoryService categoryService;
    private final DocumentClassifierService classifier;
    private final KbKnowledgeBaseMapper kbMapper;
    private final KbChunkMapper chunkMapper;

    @GetMapping("/list")
    public Result<List<KbCategory>> list() {
        return Result.success(categoryService.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody KbCategory c) {
        return Result.success(categoryService.create(c));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody KbCategory c) {
        c.setId(id);
        categoryService.update(c);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }

    /** 用户手动改文档分类 → 锁定，AI 不再覆盖 */
    @PutMapping("/document/{kbId}/category")
    public Result<Void> setDocumentCategory(@PathVariable Long kbId, @RequestBody SetCategoryDTO dto) {
        if (dto.getCode() == null || dto.getCode().isBlank()) {
            return Result.error("category code 不能为空");
        }
        if (categoryService.byCode(dto.getCode()) == null) {
            return Result.error("分类不存在: " + dto.getCode());
        }
        KbKnowledgeBase patch = new KbKnowledgeBase();
        patch.setId(kbId);
        patch.setCategory(dto.getCode());
        patch.setCategoryUserSet(1);
        kbMapper.updateById(patch);
        return Result.success();
    }

    /** 强制重跑 LLM 分类（清掉 user_set 锁，再分类） */
    @PostMapping("/document/{kbId}/reclassify")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> reclassify(@PathVariable Long kbId) {
        KbKnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) return Result.error("文档不存在");

        // 清锁，让 classify 可以写入
        if (Integer.valueOf(1).equals(kb.getCategoryUserSet())) {
            KbKnowledgeBase unlock = new KbKnowledgeBase();
            unlock.setId(kbId);
            unlock.setCategoryUserSet(0);
            kbMapper.updateById(unlock);
            kb.setCategoryUserSet(0);
        }

        String text = collectKbText(kbId);
        if (text.isBlank()) return Result.error("文档无可读 chunk，无法分类");

        classifier.classify(kbId, kb.getName(), 0, text);
        return Result.success();
    }

    /** 把该 kb 的前若干 chunk 内容按 chunkIndex 拼起来当样本 */
    private String collectKbText(Long kbId) {
        List<KbChunk> list = chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getKbId, kbId)
                        .orderByAsc(KbChunk::getChunkIndex)
                        .last("LIMIT 50")
        );
        StringBuilder sb = new StringBuilder();
        for (KbChunk c : list) {
            if (c.getContent() != null) sb.append(c.getContent()).append('\n');
            if (sb.length() > 5000) break;
        }
        return sb.toString();
    }

    @Data
    public static class SetCategoryDTO {
        private String code;
    }
}
