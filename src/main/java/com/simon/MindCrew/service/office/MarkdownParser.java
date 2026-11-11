package com.simon.MindCrew.service.office;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown 解析器（PPT / Word 导出专用，不做完整 CommonMark 兼容）
 *
 * 支持：
 *   # / ## / ### 标题            → HEADING1 / HEADING2 / HEADING3
 *   - / * / + 列表               → BULLET
 *   1. / 1) 有序列表             → ORDERED
 *   普通段落（连续行合并）        → PARAGRAPH
 *   ``` 代码块（按段落输出）      → CODE（逐行）
 *   | 表格 | 按行转文本          → TABLE（逐行）
 *
 * 行内标记（**加粗**、`代码`、[链接](url)）在解析阶段统一清洗为纯文本。
 */
@Component
public class MarkdownParser {

    public enum BlockType {
        HEADING1, HEADING2, HEADING3, BULLET, ORDERED, PARAGRAPH, CODE, TABLE
    }

    /** 一段解析结果：类型 + 已清洗的纯文本 */
    public record Block(BlockType type, String text) {
    }

    private static final Pattern ORDERED_PATTERN = Pattern.compile("^\\d+[.)、]\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR =
            Pattern.compile("^\\|?[\\s:|-]+\\|?$");
    private static final Pattern IMAGE_LINK = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    public List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return blocks;
        }

        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        boolean inCode = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // 代码块：忽略样式，逐行按段落输出
            if (line.startsWith("```")) {
                flushParagraph(paragraph, blocks);
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                if (!line.isEmpty()) blocks.add(new Block(BlockType.CODE, stripInline(line)));
                continue;
            }

            if (line.isEmpty()) {
                flushParagraph(paragraph, blocks);
                continue;
            }

            // 表格行：分隔行（|---|:--:|）跳过，其余按单元格文本拼接
            if (line.startsWith("|") && line.endsWith("|") && line.length() > 1) {
                flushParagraph(paragraph, blocks);
                if (TABLE_SEPARATOR.matcher(line.replace(" ", "")).matches()) continue;
                String cells = line.substring(1, line.length() - 1).trim();
                if (cells.isEmpty()) continue;
                String joined = String.join(" | ", cells.split("\\s*\\|\\s*"));
                blocks.add(new Block(BlockType.TABLE, stripInline(joined)));
                continue;
            }

            // 标题
            if (line.startsWith("#")) {
                flushParagraph(paragraph, blocks);
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String heading = line.substring(level).trim();
                if (heading.isEmpty()) continue;
                if (level == 1) {
                    blocks.add(new Block(BlockType.HEADING1, stripInline(heading)));
                } else if (level == 2) {
                    blocks.add(new Block(BlockType.HEADING2, stripInline(heading)));
                } else {
                    blocks.add(new Block(BlockType.HEADING3, stripInline(heading)));
                }
                continue;
            }

            // 无序列表
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                flushParagraph(paragraph, blocks);
                String text = line.substring(2).trim();
                if (!text.isEmpty()) blocks.add(new Block(BlockType.BULLET, stripInline(text)));
                continue;
            }

            // 有序列表
            Matcher ordered = ORDERED_PATTERN.matcher(line);
            if (ordered.matches()) {
                flushParagraph(paragraph, blocks);
                String text = ordered.group(1).trim();
                if (!text.isEmpty()) blocks.add(new Block(BlockType.ORDERED, stripInline(text)));
                continue;
            }

            // 引用块 / 分割线：按普通段落处理（去掉标记）
            if (line.startsWith(">")) {
                flushParagraph(paragraph, blocks);
                String text = line.substring(1).trim();
                if (!text.isEmpty()) blocks.add(new Block(BlockType.PARAGRAPH, stripInline(text)));
                continue;
            }
            if (line.matches("^[-*_]{3,}$")) {
                flushParagraph(paragraph, blocks);
                continue;
            }

            // 普通段落：连续行合并
            String cleaned = stripInline(line);
            if (cleaned.isEmpty()) continue;
            if (paragraph.length() > 0) {
                paragraph.append(needsSpace(paragraph.charAt(paragraph.length() - 1), cleaned.charAt(0)) ? " " : "");
            }
            paragraph.append(cleaned);
        }
        flushParagraph(paragraph, blocks);
        return blocks;
    }

    /**
     * 清洗行内 Markdown：图片/链接只留文字、去掉 ** __ ` 等标记。
     * 供 PPT / DOCX 渲染前统一调用。
     */
    public static String stripInline(String text) {
        if (text == null) return "";
        String result = text;
        result = IMAGE_LINK.matcher(result).replaceAll("$1");
        result = LINK.matcher(result).replaceAll("$1");
        result = result.replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace("~~", "");
        return result.trim();
    }

    private void flushParagraph(StringBuilder paragraph, List<Block> blocks) {
        if (paragraph.length() == 0) return;
        String text = paragraph.toString().trim();
        paragraph.setLength(0);
        if (!text.isEmpty()) blocks.add(new Block(BlockType.PARAGRAPH, text));
    }

    /** 中文之间不补空格，字母数字之间补一个空格 */
    private boolean needsSpace(char previous, char next) {
        return isAsciiWord(previous) && isAsciiWord(next);
    }

    private boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
