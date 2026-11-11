package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库服务接口
 */
public interface KnowledgeBaseService {

    /**
     * 上传并解析文档（异步）
     * @return 知识库记录ID
     */
    Long uploadDocument(MultipartFile file, String name, String category, String description, Long userId);

    /**
     * 分页查询知识库列表
     */
    Page<MedKnowledgeBase> listKnowledge(Integer current, Integer size, String category, String status);

    /**
     * 获取知识库详情
     */
    MedKnowledgeBase getById(Long id);

    /**
     * 删除知识库（同步删除 Milvus 向量）
     */
    void deleteById(Long id);

    /**
     * 获取所有分类
     */
    java.util.List<String> listCategories();

    /**
     * 重新处理文档（处理失败时重试）
     */
    void reprocess(Long id);

    /**
     * 任务 7 · 切换 KB 可见性
     */
    void updateVisibility(Long id, String visibility);
}
