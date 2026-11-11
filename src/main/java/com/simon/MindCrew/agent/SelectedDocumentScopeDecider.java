package com.simon.MindCrew.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class SelectedDocumentScopeDecider {

    private static final Pattern DOCUMENT_REFERENCE_PATTERN = Pattern.compile(
            "这份文档|这个文档|该文档|这篇文档|这篇文章|该文章|这份材料|该材料|这份文件|该文件|这份报告|该报告|当前文档|选中的文档|这篇内容|本文档|本文");

    private static final Pattern SUMMARY_INTENT_PATTERN = Pattern.compile(
            "总结|概述|摘要|主要内容|核心内容|讲了什么|讲的什么|说了什么|内容概括|提炼要点|梳理重点|总结一下|概括一下");

    /**
     * 判断是否走文档直读模式。
     * <p>只要用户选了知识库，就用文档直读（避免全局检索稀释相关性）。</p>
     */
    public boolean shouldDirectRead(List<Long> kbIds, String question, boolean explicitScope) {
        // 只有用户「显式选择」了知识库才走文档直读；
        // 否则（默认 ACL 全量范围）走正常多路检索，避免知识库数量大时全量装载切片。
        if (!explicitScope) {
            return false;
        }
        if (kbIds == null || kbIds.isEmpty() || question == null || question.isBlank()) {
            return false;
        }
        return true;
    }
}
