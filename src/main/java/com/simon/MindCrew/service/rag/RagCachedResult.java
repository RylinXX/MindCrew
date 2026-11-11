package com.simon.MindCrew.service.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * RAG 缓存结果对象
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RagCachedResult implements Serializable {

    /** 完整回答文本（含安全提示追加） */
    private String answer;

    /** 来源引用列表 */
    private List<Map<String, Object>> sources;

    /** 是否触发兜底 */
    private boolean fallback;

    /** 是否触发高风险提示 */
    private boolean emergency;

    /** 改写后的查询 */
    private String rewrittenQuery;

    /** 检索日志（供前端可视化） */
    private Map<String, Object> retrievalLog;
}
