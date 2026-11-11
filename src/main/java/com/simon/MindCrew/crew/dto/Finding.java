package com.simon.MindCrew.crew.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个 Researcher 子任务的调研结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Finding {

    /** 对应 PlanItem.index */
    private Integer planIndex;

    /** 子任务标题 */
    private String title;

    /** 章节名 */
    private String section;

    /** 调研结论文本（Researcher 整理后的要点） */
    private String summary;

    /** 引用来源列表（用于最终报告标注） */
    private List<SourceRef> sources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceRef {
        // ── 通用字段 ─────────────────────────────────
        /** 文档名 */
        private String docName;
        /** 章节（文档/PPT 用，音视频为空）*/
        private String chapter;
        /** 页码（PDF/PPT 用） */
        private Integer pageNumber;
        /** 节选片段 */
        private String excerpt;
        /** 相关度评分 */
        private Double score;

        // ── 时间戳溯源（任务 2 新增）────────────────
        /**
         * 媒体类型，前端按此渲染不同 UI：
         *   document | pdf | pptx | xlsx | image | audio | video | text
         */
        private String mediaType;

        /** 原始知识库 ID（用于查询 source_url） */
        private Long knowledgeBaseId;

        /** 原始文件可访问 URL（OSS/MinIO 预签名链接，用于前端预览/播放）*/
        private String sourceUrl;

        /** 音视频起始毫秒（仅 audio/video 有值）*/
        private Long startMs;

        /** 音视频结束毫秒 */
        private Long endMs;

        /** 说话人 ID（diarization 开启时）*/
        private String speakerId;
    }
}
