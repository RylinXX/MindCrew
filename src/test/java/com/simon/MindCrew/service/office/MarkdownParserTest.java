package com.simon.MindCrew.service.office;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数字员工 · Markdown 解析单元测试
 */
class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void parsesHeadingLevelsAndLists() {
        String md = "# 一级\n## 二级\n### 三级\n- 项目 A\n- 项目 B\n\n1. 第一步\n\n普通段落";
        List<MarkdownParser.Block> blocks = parser.parse(md);

        assertTrue(blocks.stream().anyMatch(b -> b.type() == MarkdownParser.BlockType.HEADING1 && b.text().equals("一级")));
        assertTrue(blocks.stream().anyMatch(b -> b.type() == MarkdownParser.BlockType.HEADING2 && b.text().equals("二级")));
        assertTrue(blocks.stream().anyMatch(b -> b.type() == MarkdownParser.BlockType.HEADING3 && b.text().equals("三级")));
        assertEquals(2, blocks.stream().filter(b -> b.type() == MarkdownParser.BlockType.BULLET).count());
        assertTrue(blocks.stream().anyMatch(b -> b.type() == MarkdownParser.BlockType.ORDERED));
        assertTrue(blocks.stream().anyMatch(b -> b.type() == MarkdownParser.BlockType.PARAGRAPH && b.text().contains("普通段落")));
    }

    @Test
    void stripsInlineMarkdown() {
        String md = "## 标题 **加粗** 和 `代码` 与 [链接](https://example.com)";
        List<MarkdownParser.Block> blocks = parser.parse(md);
        assertEquals(1, blocks.size());
        String text = blocks.get(0).text();
        assertFalse(text.contains("**"));
        assertFalse(text.contains("`"));
        assertTrue(text.contains("加粗"));
        assertTrue(text.contains("链接"));
        assertFalse(text.contains("https://example.com"));
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
    }
}
