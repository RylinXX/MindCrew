package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档实体
 * 对应数据表: kb_knowledge_base
 */
@Data
@TableName("kb_knowledge_base")
public class KbKnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库/文档名称 */
    private String name;

    /** 分类（技术/法律/产品/通用等） · 关联 kb_category.code */
    private String category;

    /** LLM 提取的标签数组（JSON 字符串，如 ["合同","2024","客户A"]） */
    private String tags;

    /** LLM 生成的 100-200 字摘要 */
    private String summary;

    /** 是否用户手动指定（1 = 锁定，AI 不再覆盖） */
    private Integer categoryUserSet;

    /**
     * 可见性 · 任务 7 职位独立 KB
     *   public  · 所有人可读（默认，兼容旧 KB）
     *   scoped  · 按 kb_acl 控制
     *   private · 仅创建者可见
     */
    private String visibility;

    private String description;

    /** 原始文件存储路径 (MinIO) */
    private String fileUrl;

    /** 文件类型: pdf/docx/md/txt */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 切片数量 */
    private Integer chunkCount;

    /** 状态: uploading/processing/ready/error */
    private String status;

    private String errorMsg;

    /** 创建者用户ID */
    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
