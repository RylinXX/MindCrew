package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工校正的标准问答对 · 任务 6 的核心数据
 *
 * Q&A pair 一旦审核通过：
 *  - 存入 qa_golden_pair（DB）+ Milvus golden 集合（向量）
 *  - 用户后续问相似问题时，Agent 在最前端做相似度搜索，命中直接返回 standard_answer
 *  - 不命中才走完整 RAG 流程
 */
@Data
@TableName("qa_golden_pair")
public class QaGoldenPair {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String question;
    /** 归一化后的 question · 用于快速精确匹配 */
    private String questionNorm;
    private String standardAnswer;

    /** 引用来源 JSON */
    private String sourcesJson;

    /** Milvus 中对应向量的主键 */
    private String milvusId;

    /** 来源反馈 ID（这条 golden pair 是从哪条 feedback 来的） */
    private Long sourceFeedbackId;

    private String category;
    private String tags;

    private Integer enabled;
    private Integer hitCount;
    private LocalDateTime lastHitAt;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
