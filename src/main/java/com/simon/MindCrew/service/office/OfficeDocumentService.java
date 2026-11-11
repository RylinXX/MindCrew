package com.simon.MindCrew.service.office;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.service.office.ContractTemplateRegistry.RenderedContract;
import com.simon.MindCrew.service.office.MarkdownParser.Block;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Office 文档生成 · 纯 POI 程序化排版（不依赖二进制模板文件）
 *
 *  - PPTX：16:9（13.333in x 7.5in = 960 x 540 point），封面 / 目录 / 章节分页 / 页脚
 *  - DOCX：标题 / Heading 层级 / 列表 / 正文 11pt / 1.5 倍行距 / 页脚
 *  - 合同：ContractTemplateRegistry 校验并渲染正文，本类只负责排版
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeDocumentService {

    public static final String CONTENT_TYPE_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    public static final String CONTENT_TYPE_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String CONTENT_TYPE_MARKDOWN = "text/markdown;charset=UTF-8";

    /** 16:9 页面：13.333in x 7.5in，POI 坐标单位为 point（1in = 72pt） */
    private static final int SLIDE_WIDTH = 960;
    private static final int SLIDE_HEIGHT = 540;
    private static final int MAX_BULLETS_PER_SLIDE = 6;
    private static final int MAX_LINE_CHARS = 200;

    private static final String FONT_CN = "微软雅黑";
    private static final String THEME_HEX = "3D5AFE";
    private static final String TEXT_HEX = "333333";
    private static final String GRAY_HEX = "888888";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MarkdownParser markdownParser;
    private final ContractTemplateRegistry contractTemplateRegistry;

    // ============================================================
    // PPTX
    // ============================================================

    public byte[] toPptx(String title, String subtitle, String markdown) {
        String safeTitle = isBlank(title) ? "MindCrew 报告" : title.trim();
        String safeSubtitle = isBlank(subtitle)
                ? "由 MindCrew 数字员工自动生成 · " + LocalDate.now().format(DATE_FMT)
                : subtitle.trim();

        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension(SLIDE_WIDTH, SLIDE_HEIGHT));

            List<Block> blocks = markdownParser.parse(markdown);
            List<Section> sections = buildSections(blocks, safeTitle);

            createCover(ppt, safeTitle, safeSubtitle);
            if (sections.size() >= 2) createToc(ppt, sections);
            for (Section section : sections) createSectionSlides(ppt, section);
            addSlideFooters(ppt);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[Office] PPTX 生成失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.ERROR.getCode(), "文档生成失败: " + e.getMessage());
        }
    }

    /** 把 Markdown 结构折叠成章节：`##`（以及 `#`）为章节标题，其它内容为 bullet */
    private List<Section> buildSections(List<Block> blocks, String fallbackTitle) {
        List<Section> sections = new ArrayList<>();
        Section current = null;

        for (Block block : blocks) {
            switch (block.type()) {
                case HEADING1, HEADING2 -> {
                    current = new Section(block.text(), new ArrayList<>());
                    sections.add(current);
                }
                case HEADING3 -> {
                    current = ensureSection(sections, current, fallbackTitle);
                    current.lines.add(new SlideLine(block.text(), true));
                }
                case BULLET, ORDERED, PARAGRAPH, TABLE, CODE -> {
                    String text = truncate(block.text(), MAX_LINE_CHARS);
                    if (text.isEmpty()) continue;
                    current = ensureSection(sections, current, fallbackTitle);
                    current.lines.add(new SlideLine(text, false));
                }
            }
        }

        // 去掉没有任何内容的章节（例如仅出现一个标题）
        sections.removeIf(section -> section.lines.isEmpty());
        // 无任何标题时按段落内容分页
        if (sections.isEmpty()) {
            sections.add(new Section(fallbackTitle, new ArrayList<>()));
        }
        return sections;
    }

    private Section ensureSection(List<Section> sections, Section current, String fallbackTitle) {
        if (current != null) return current;
        Section section = new Section(fallbackTitle, new ArrayList<>());
        sections.add(section);
        return section;
    }

    private void createCover(XMLSlideShow ppt, String title, String subtitle) {
        XSLFSlide slide = ppt.createSlide();

        XSLFTextBox titleBox = createTextBox(slide, 70, 165, 820, 110);
        addText(titleBox, title, 40, true, THEME_HEX, TextParagraph.TextAlign.LEFT, 0);

        XSLFTextBox subtitleBox = createTextBox(slide, 70, 305, 820, 50);
        addText(subtitleBox, subtitle, 16, false, GRAY_HEX, TextParagraph.TextAlign.LEFT, 0);

        XSLFTextBox bottomBox = createTextBox(slide, 70, 470, 820, 30);
        addText(bottomBox, "Generated by MindCrew", 12, false, GRAY_HEX,
                TextParagraph.TextAlign.LEFT, 0);
    }

    private void createToc(XMLSlideShow ppt, List<Section> sections) {
        XSLFSlide slide = ppt.createSlide();
        XSLFTextBox heading = createTextBox(slide, 60, 40, 840, 60);
        addText(heading, "目录", 30, true, THEME_HEX, TextParagraph.TextAlign.LEFT, 0);

        XSLFTextBox listBox = createTextBox(slide, 80, 130, 800, 350);
        int limit = Math.min(sections.size(), 10);
        for (int i = 0; i < limit; i++) {
            addText(listBox, (i + 1) + ". " + sections.get(i).title, 18, false, TEXT_HEX,
                    TextParagraph.TextAlign.LEFT, 10);
        }
        if (sections.size() > limit) {
            addText(listBox, "……", 18, false, GRAY_HEX, TextParagraph.TextAlign.LEFT, 0);
        }
    }

    private void createSectionSlides(XMLSlideShow ppt, Section section) {
        List<SlideLine> lines = section.lines;
        int pageCount = (lines.size() + MAX_BULLETS_PER_SLIDE - 1) / MAX_BULLETS_PER_SLIDE;
        if (pageCount <= 0) pageCount = 1;

        for (int page = 0; page < pageCount; page++) {
            XSLFSlide slide = ppt.createSlide();

            XSLFTextBox titleBox = createTextBox(slide, 55, 32, 850, 60);
            addText(titleBox, truncate(section.title, 80), 28, true, THEME_HEX,
                    TextParagraph.TextAlign.LEFT, 0);

            XSLFTextBox bodyBox = createTextBox(slide, 62, 118, 840, 370);
            int from = page * MAX_BULLETS_PER_SLIDE;
            int to = Math.min(from + MAX_BULLETS_PER_SLIDE, lines.size());
            for (int i = from; i < to; i++) {
                SlideLine line = lines.get(i);
                if (line.bold) {
                    addText(bodyBox, line.text, 18, true, THEME_HEX, TextParagraph.TextAlign.LEFT, 8);
                } else {
                    addText(bodyBox, "• " + line.text, 17, false, TEXT_HEX, TextParagraph.TextAlign.LEFT, 10);
                }
            }
        }
    }

    private void addSlideFooters(XMLSlideShow ppt) {
        List<XSLFSlide> slides = ppt.getSlides();
        int contentPages = Math.max(slides.size() - 1, 1);
        for (int i = 0; i < slides.size(); i++) {
            if (i == 0) continue; // 封面不显示页脚
            XSLFSlide slide = slides.get(i);
            XSLFTextBox box = createTextBox(slide, 60, 505, 840, 22);
            String text = (i) + " / " + contentPages + "  ·  MindCrew · Agentic RAG";
            addText(box, text, 10, false, GRAY_HEX, TextParagraph.TextAlign.RIGHT, 0);
        }
    }

    private XSLFTextBox createTextBox(XSLFSlide slide, double x, double y, double width, double height) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, width, height));
        // 清掉原型自带的空段落，避免正文前多一行空白
        box.clearText();
        return box;
    }

    private void addText(XSLFTextBox box, String text, double fontSize, boolean bold,
                         String hexColor, TextParagraph.TextAlign align, double spaceAfter) {
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        paragraph.setTextAlign(align);
        paragraph.setSpaceAfter(spaceAfter);
        XSLFTextRun run = paragraph.addNewTextRun();
        run.setText(text == null ? "" : text);
        run.setFontFamily(FONT_CN);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontColor(parseColor(hexColor));
    }

    private static Color parseColor(String hex) {
        return new Color(Integer.parseInt(hex, 16));
    }

    // ============================================================
    // DOCX · Markdown
    // ============================================================

    public byte[] toDocx(String title, String markdown) {
        String docTitle = isBlank(title) ? "MindCrew 文档" : title.trim();
        List<Block> blocks = markdownParser.parse(markdown);

        try (XWPFDocument document = new XWPFDocument()) {
            addDocxTitle(document, docTitle);

            boolean titleBlockSkipped = false;
            int orderedIndex = 0;
            for (Block block : blocks) {
                switch (block.type()) {
                    case HEADING1 -> {
                        if (!titleBlockSkipped && block.text().equals(docTitle)) {
                            titleBlockSkipped = true;
                            continue;
                        }
                        addDocxHeading(document, block.text(), 1);
                        orderedIndex = 0;
                    }
                    // 契约：`##` → Heading1，`###` → Heading2
                    case HEADING2 -> {
                        addDocxHeading(document, block.text(), 1);
                        orderedIndex = 0;
                    }
                    case HEADING3 -> addDocxHeading(document, block.text(), 2);
                    case BULLET -> addDocxBody(document, "• " + block.text());
                    case ORDERED -> {
                        orderedIndex++;
                        addDocxBody(document, orderedIndex + ". " + block.text());
                    }
                    case TABLE -> addDocxBody(document, block.text());
                    case CODE, PARAGRAPH -> addDocxBody(document, block.text());
                }
            }

            addDocxFooter(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[Office] DOCX 生成失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.ERROR.getCode(), "文档生成失败: " + e.getMessage());
        }
    }

    private void addDocxTitle(XWPFDocument document, String title) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(240);
        XWPFRun run = paragraph.createRun();
        run.setText(title);
        run.setBold(true);
        run.setFontSize(22);
        run.setFontFamily(FONT_CN);
        run.setColor(THEME_HEX);
    }

    private void addDocxHeading(XWPFDocument document, String text, int level) {
        String cleaned = truncate(text, 300);
        if (cleaned.isEmpty()) return;
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        paragraph.setSpacingBefore(level == 1 ? 280 : 200);
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setText(cleaned);
        run.setBold(true);
        run.setFontSize(level == 1 ? 16 : level == 2 ? 14 : 12);
        run.setFontFamily(FONT_CN);
        run.setColor(THEME_HEX);
    }

    private void addDocxBody(XWPFDocument document, String text) {
        String cleaned = text == null ? "" : text.trim();
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(80);
        paragraph.setSpacingBetween(1.5);
        if (cleaned.isEmpty()) return;
        XWPFRun run = paragraph.createRun();
        run.setText(cleaned);
        run.setFontSize(11);
        run.setFontFamily(FONT_CN);
    }

    private void addDocxFooter(XWPFDocument document) {
        XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph paragraph = footer.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText("MindCrew 数字员工 · " + LocalDate.now().format(DATE_FMT));
        run.setFontSize(9);
        run.setFontFamily(FONT_CN);
        run.setColor(GRAY_HEX);
    }

    // ============================================================
    // DOCX · 合同
    // ============================================================

    public byte[] toContractDocx(String templateId, Map<String, Object> fields) {
        // 校验 / 渲染失败直接抛 BusinessException(400)，不包装成 500
        RenderedContract contract = contractTemplateRegistry.render(templateId, fields);

        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph titleParagraph = document.createParagraph();
            titleParagraph.setAlignment(ParagraphAlignment.CENTER);
            titleParagraph.setSpacingAfter(120);
            XWPFRun titleRun = titleParagraph.createRun();
            titleRun.setText(contract.title());
            titleRun.setBold(true);
            titleRun.setFontSize(22);
            titleRun.setFontFamily(FONT_CN);
            titleRun.setColor(THEME_HEX);

            XWPFParagraph noParagraph = document.createParagraph();
            noParagraph.setAlignment(ParagraphAlignment.CENTER);
            noParagraph.setSpacingAfter(280);
            XWPFRun noRun = noParagraph.createRun();
            noRun.setText("合同编号：" + contract.contractNo());
            noRun.setFontSize(10);
            noRun.setFontFamily(FONT_CN);
            noRun.setColor(GRAY_HEX);

            for (String line : contract.lines()) {
                if (line == null) continue;
                if (line.isBlank()) {
                    document.createParagraph();
                } else if (line.startsWith("## ")) {
                    addContractHeading(document, line.substring(3).trim());
                } else {
                    addDocxBody(document, line);
                }
            }

            addDocxFooter(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[Office] 合同 DOCX 生成失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.ERROR.getCode(), "文档生成失败: " + e.getMessage());
        }
    }

    private void addContractHeading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading2");
        paragraph.setSpacingBefore(220);
        paragraph.setSpacingAfter(100);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(13);
        run.setFontFamily(FONT_CN);
        run.setColor(THEME_HEX);
    }

    // ============================================================
    // 通用工具
    // ============================================================

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "…";
    }

    /** PPT 章节中间态 */
    private static final class Section {
        private final String title;
        private final List<SlideLine> lines;

        private Section(String title, List<SlideLine> lines) {
            this.title = title == null ? "" : title;
            this.lines = lines;
        }
    }

    /** PPT 单行内容：bold=true 表示 ### 小标题 */
    private record SlideLine(String text, boolean bold) {
    }
}
