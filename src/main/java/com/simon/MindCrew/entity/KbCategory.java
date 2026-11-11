package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库分类字典
 * 对应数据表: kb_category
 */
@Data
@TableName("kb_category")
public class KbCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 英文 code，写入 kb_knowledge_base.category */
    private String code;

    /** 中文展示名 */
    private String name;

    /** 父分类 ID（NULL = 一级） */
    private Long parentId;

    /** LLM 分类提示用 — 越具体越准确 */
    private String description;

    private String icon;
    private String color;
    private Integer sortOrder;
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
