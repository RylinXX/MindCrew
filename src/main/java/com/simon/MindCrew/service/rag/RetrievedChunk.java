package com.simon.MindCrew.service.rag;

import lombok.Data;

/**
 * 检索结果切片
 */
@Data
public class RetrievedChunk {

    private String id;

    /** 切片内容 */
    private String content;

    /** 相关性得分（越高越相关）*/
    private float score;

    /** 科室分类 */
    private String category;

    /** 内容类型：definition/symptom/treatment/drug/general */
    private String contentType;

    /** 章节名 */
    private String chapter;

    /** 页码 */
    private int pageNumber;

    /** 知识库 ID */
    private Long knowledgeBaseId;

    /** 知识库文档名（join 后填入）*/
    private String sourceName;

    /** 来源标注文本（用于前端显示）*/
    private String sourceRef;

    /** 检索来源标记 */
    private Source source;

    /** 切片在文档中的位置索引（0-based），按文档原始顺序递增。用于位置感知排序。 */
    private Integer chunkIndex;

    /** RRF 融合后的排名 */
    private int rrfRank;

    /** Cross-Encoder 重排序得分 */
    private float rerankScore;

    // ── 时间戳溯源元数据（任务 2 新增）─────────────
    /** 音视频起始毫秒（仅 audio/video chunk 有值） */
    private Long startMs;

    /** 音视频结束毫秒 */
    private Long endMs;

    /** 说话人 ID（音频 diarization 开启时） */
    private String speakerId;

    /** 原始文件类型: pdf / docx / pptx / xlsx / image / audio / video / text */
    private String mediaType;

    /** 原始文件 OSS/MinIO 对象名（用于生成预签名 URL） */
    private String sourceObjectName;

    public enum Source {
        VECTOR, BM25, HYBRID, WEB
    }
}
