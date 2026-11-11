package com.simon.MindCrew.service.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import com.opencsv.CSVReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档文本提取器
 *
 * 支持格式：
 *   PDF                       — Apache PDFBox（按页提取）
 *   Word(.docx)               — POI XWPF
 *   Word(.doc) 老格式          — POI HWPF
 *   PowerPoint(.pptx)         — POI XSLF（按页提取，含备注）
 *   PowerPoint(.ppt) 老格式    — POI HSLF
 *   Excel(.xlsx / .xls)       — POI XSSF/HSSF（按 Sheet 提取，转 Markdown 表格）
 *   CSV                       — OpenCSV
 *   WPS (.wps)                — 经 LibreOffice 转 .docx 后走 DOCX 解析
 *   TXT / Markdown            — UTF-8 读取
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExtractor {

    private final OfficeConverter officeConverter;
    private final VisionRecognizer visionRecognizer;
    private final AudioTranscriber audioTranscriber;

    /** PDF 单页文本少于此阈值时，触发整页 OCR 兜底（扫描型 PDF 场景） */
    private static final int PDF_OCR_FALLBACK_THRESHOLD = 20;
    /** PDF OCR 兜底渲染时的 DPI */
    private static final int PDF_OCR_RENDER_DPI = 200;

    /** 视觉/OCR 能力开关。无视觉模型 Key 时置 false，扫描页直接跳过 OCR，避免拖慢批量入库 */
    @org.springframework.beans.factory.annotation.Value("${vision.enabled:true}")
    private boolean visionEnabled;

    // ============================================================
    // PDF
    // ============================================================
    /**
     * 提取 PDF 文本。原生文本 PDF 直接走 PDFBox；
     * 扫描型 PDF（某页文字少于阈值）自动渲染为图片走 OCR 兜底。
     */
    public List<PageContent> extractPdf(InputStream inputStream) {
        List<PageContent> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(inputStream))) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);
            int ocrPageCount = 0;

            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document).trim();

                if (text.length() < PDF_OCR_FALLBACK_THRESHOLD) {
                    // 文字太少 → 可能是扫描页，渲染成图片走 OCR
                    String ocrText = tryOcrPdfPage(renderer, i - 1);
                    if (ocrText != null && !ocrText.isBlank()) {
                        text = ocrText;
                        ocrPageCount++;
                    }
                }
                if (!text.isEmpty()) pages.add(new PageContent(i, text));
            }
            log.info("[Extractor] PDF: {} pages, {} non-empty, {} via OCR fallback",
                    totalPages, pages.size(), ocrPageCount);
        } catch (IOException e) {
            log.error("PDF 提取失败", e);
            throw new RuntimeException("PDF 解析失败: " + e.getMessage());
        }
        return pages;
    }

    /** 把 PDF 某页渲染为 PNG 后走 OCR */
    private String tryOcrPdfPage(PDFRenderer renderer, int pageIndex) {
        if (!visionEnabled) return null;
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, PDF_OCR_RENDER_DPI, ImageType.RGB);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bos);
            VisionRecognizer.VisionResult vr = visionRecognizer.recognize(bos.toByteArray(), "image/png");
            if (vr.success()) {
                return vr.ocrText();
            }
        } catch (Exception e) {
            log.warn("[Extractor] PDF 第 {} 页 OCR 兜底失败: {}", pageIndex + 1, e.getMessage());
        }
        return null;
    }

    // ============================================================
    // Word
    // ============================================================
    public String extractDocx(InputStream inputStream) {
        try (XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            log.info("[Extractor] DOCX: {} chars", text.length());
            return text;
        } catch (IOException e) {
            throw new RuntimeException("DOCX 解析失败: " + e.getMessage());
        }
    }

    /** 老 .doc 格式 */
    public String extractDoc(InputStream inputStream) {
        try (HWPFDocument doc = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(doc)) {
            String text = extractor.getText();
            log.info("[Extractor] DOC: {} chars", text.length());
            return text;
        } catch (IOException e) {
            throw new RuntimeException("DOC 解析失败: " + e.getMessage());
        }
    }

    // ============================================================
    // PowerPoint
    // ============================================================
    public List<PageContent> extractPptx(InputStream inputStream) {
        List<PageContent> pages = new ArrayList<>();
        try (XMLSlideShow ppt = new XMLSlideShow(inputStream)) {
            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                XSLFSlide slide = slides.get(i);

                String title = slide.getTitle();
                if (title != null && !title.isBlank()) {
                    sb.append("【标题】").append(title).append("\n");
                }
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText().trim();
                        if (!t.isEmpty() && !t.equals(title)) sb.append(t).append("\n");
                    }
                }
                if (slide.getNotes() != null) {
                    String notes = extractSlideNotes(slide);
                    if (!notes.isBlank()) sb.append("【备注】").append(notes);
                }
                String text = sb.toString().trim();
                if (!text.isEmpty()) pages.add(new PageContent(i + 1, text));
            }
            log.info("[Extractor] PPTX: {} slides, {} non-empty", slides.size(), pages.size());
        } catch (IOException e) {
            throw new RuntimeException("PPTX 解析失败: " + e.getMessage());
        }
        return pages;
    }

    private String extractSlideNotes(XSLFSlide slide) {
        StringBuilder sb = new StringBuilder();
        if (slide.getNotes() == null) return "";
        for (XSLFShape s : slide.getNotes().getShapes()) {
            if (s instanceof XSLFTextShape ts) sb.append(ts.getText()).append("\n");
        }
        return sb.toString().trim();
    }

    /** 老 .ppt 格式 */
    public List<PageContent> extractPpt(InputStream inputStream) {
        List<PageContent> pages = new ArrayList<>();
        try (HSLFSlideShow ppt = new HSLFSlideShow(inputStream)) {
            List<HSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                HSLFSlide slide = slides.get(i);
                String title = slide.getTitle();
                if (title != null && !title.isBlank()) {
                    sb.append("【标题】").append(title).append("\n");
                }
                for (HSLFShape s : slide.getShapes()) {
                    if (s instanceof HSLFTextShape ts) {
                        String t = ts.getText().trim();
                        if (!t.isEmpty() && !t.equals(title)) sb.append(t).append("\n");
                    }
                }
                String text = sb.toString().trim();
                if (!text.isEmpty()) pages.add(new PageContent(i + 1, text));
            }
            log.info("[Extractor] PPT: {} slides, {} non-empty", slides.size(), pages.size());
        } catch (IOException e) {
            throw new RuntimeException("PPT 解析失败: " + e.getMessage());
        }
        return pages;
    }

    // ============================================================
    // Excel
    // ============================================================
    /** 把 Excel 转成"每个 Sheet 一份 Markdown 表格"的形式，便于 RAG 检索 */
    public List<PageContent> extractExcel(InputStream inputStream) {
        List<PageContent> pages = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            int sheetCount = workbook.getNumberOfSheets();
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String md = sheetToMarkdown(sheet, formatter);
                if (!md.isBlank()) {
                    pages.add(new PageContent(s + 1,
                            "【工作表】" + sheet.getSheetName() + "\n\n" + md));
                }
            }
            log.info("[Extractor] Excel: {} sheets, {} non-empty", sheetCount, pages.size());
        } catch (IOException e) {
            throw new RuntimeException("Excel 解析失败: " + e.getMessage());
        }
        return pages;
    }

    /** 把单个 Sheet 渲染为 Markdown 表格。空行跳过，列宽自适应。 */
    private String sheetToMarkdown(Sheet sheet, DataFormatter formatter) {
        StringBuilder sb = new StringBuilder();
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) return "";

        // 取第一行作为表头
        Row firstRow = sheet.getRow(sheet.getFirstRowNum());
        if (firstRow == null) return "";
        int colCount = firstRow.getLastCellNum();

        // 表头
        sb.append("|");
        for (int c = 0; c < colCount; c++) {
            sb.append(" ").append(getCellText(firstRow.getCell(c), formatter)).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < colCount; c++) sb.append(":---|");
        sb.append("\n");

        // 数据行
        for (int r = sheet.getFirstRowNum() + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean allEmpty = true;
            StringBuilder line = new StringBuilder("|");
            for (int c = 0; c < colCount; c++) {
                String val = getCellText(row.getCell(c), formatter);
                if (!val.isEmpty()) allEmpty = false;
                line.append(" ").append(val).append(" |");
            }
            if (!allEmpty) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String getCellText(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        // 公式 cell 走计算后值；其他直接格式化
        return formatter.formatCellValue(cell).replace("|", "/").replace("\n", " ").trim();
    }

    // ============================================================
    // CSV
    // ============================================================
    public String extractCsv(InputStream inputStream) {
        StringBuilder sb = new StringBuilder();
        // 尝试 UTF-8，失败回退 GBK（国内 Excel 导出常见）
        byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("CSV 读取失败: " + e.getMessage());
        }
        Charset charset = detectCharset(bytes);

        try (CSVReader reader = new CSVReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), charset))) {
            String[] header = reader.readNext();
            if (header == null) return "";

            sb.append("|");
            for (String h : header) sb.append(" ").append(h.trim()).append(" |");
            sb.append("\n|");
            for (int i = 0; i < header.length; i++) sb.append(":---|");
            sb.append("\n");

            String[] line;
            int rows = 0;
            while ((line = reader.readNext()) != null) {
                sb.append("|");
                for (String v : line) sb.append(" ").append(v.trim().replace("|", "/")).append(" |");
                sb.append("\n");
                rows++;
            }
            log.info("[Extractor] CSV: {} rows, charset={}", rows, charset);
        } catch (Exception e) {
            throw new RuntimeException("CSV 解析失败: " + e.getMessage());
        }
        return sb.toString();
    }

    private Charset detectCharset(byte[] bytes) {
        // 简单启发式：含 UTF-8 BOM 或解码无异常 → UTF-8，否则 GBK
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            try { return Charset.forName("GBK"); } catch (Exception ex) { return StandardCharsets.UTF_8; }
        }
    }

    // ============================================================
    // TXT / Markdown
    // ============================================================
    public String extractTxt(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("文本文件读取失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 图片  ·  jpg/png/webp/bmp/gif
    // ============================================================
    public String extractImage(InputStream inputStream, String fileType) {
        if (!visionEnabled) {
            log.info("[Extractor] vision.enabled=false，图片 {} 跳过视觉识别", fileType);
            return "（视觉识别未启用，仅保留文件元信息）";
        }
        try {
            byte[] bytes = inputStream.readAllBytes();
            String mime = VisionRecognizer.mimeOf(fileType);
            VisionRecognizer.VisionResult vr = visionRecognizer.recognize(bytes, mime);
            log.info("[Extractor] Image({}): ocr={} chars, desc={} chars, success={}",
                    fileType, vr.ocrText().length(), vr.description().length(), vr.success());
            String text = vr.toIndexedText();
            if (text.isBlank()) text = "（图片无可识别内容）";
            return text;
        } catch (IOException e) {
            throw new RuntimeException("图片读取失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 音频  ·  mp3/wav/m4a/aac/flac/opus/ogg/amr
    // ============================================================
    /**
     * 转写音频文件。需要传入"DashScope 可公网访问"的 URL（一般是 MinIO/OSS 预签名 URL）。
     * 每句话作为一个 PageContent 返回，时间戳格式化在文本前缀里（便于 LLM 看到时间）。
     * 调用方如果需要纯文本+时间戳元数据分开，请用 transcribeAudio() 直接拿结构化结果。
     */
    public List<PageContent> extractAudio(String audioUrl) {
        AudioTranscriber.TranscriptionResult r = audioTranscriber.transcribe(audioUrl);
        if (!r.success()) {
            throw new RuntimeException("音频识别失败: " + r.errorMsg());
        }
        List<PageContent> pages = new ArrayList<>();
        for (AudioTranscriber.Sentence s : r.sentences()) {
            String text;
            if (s.speakerId() != null) {
                text = String.format("[%s · %s] %s", s.formatTime(), s.speakerId(), s.text());
            } else {
                text = String.format("[%s] %s", s.formatTime(), s.text());
            }
            pages.add(new PageContent(s.index(), text));
        }
        log.info("[Extractor] Audio: {} sentences, total {}ms", pages.size(), r.totalDurationMs());
        return pages;
    }

    /** 直接返回带时间戳的结构化句子（推荐：让上层切片时把 start_ms/end_ms 存进 metadata）。 */
    public AudioTranscriber.TranscriptionResult transcribeAudio(String audioUrl) {
        return audioTranscriber.transcribe(audioUrl);
    }

    // ============================================================
    // HTML  ·  提取正文（去除导航/广告/footer）
    // ============================================================
    public String extractHtml(InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            String html = new String(bytes, StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html);

            // 去除明显的噪音节点
            doc.select("script, style, nav, header, footer, aside, " +
                       ".nav, .navbar, .menu, .sidebar, .footer, .ad, .advertisement, " +
                       ".comments, .related, .recommend").remove();

            // 尝试找正文容器（多种 selector 候选）
            String[] contentSelectors = {
                    "article", "main",
                    "[role=main]",
                    ".content", ".post-content", ".article-content", ".entry-content",
                    "#content", "#main", "#article"
            };
            String text = null;
            for (String sel : contentSelectors) {
                org.jsoup.nodes.Element el = doc.selectFirst(sel);
                if (el != null) {
                    String t = el.text();
                    if (t != null && t.length() >= 100) {
                        text = t;
                        break;
                    }
                }
            }
            if (text == null) {
                // 兜底：取 body 全部文本
                text = doc.body() != null ? doc.body().text() : doc.text();
            }
            // 标题前置
            String title = doc.title();
            if (title != null && !title.isBlank() && !text.startsWith(title)) {
                text = "# " + title + "\n\n" + text;
            }
            log.info("[Extractor] HTML: {} chars (after cleaning)", text.length());
            return text;
        } catch (IOException e) {
            throw new RuntimeException("HTML 解析失败: " + e.getMessage());
        }
    }

    // ============================================================
    // WPS  ·  统一经 LibreOffice 转换
    // ============================================================
    public String extractWps(InputStream inputStream) {
        try (InputStream converted = officeConverter.convertTo(inputStream, "wps", "docx")) {
            return extractDocx(converted);
        } catch (IOException e) {
            throw new RuntimeException("WPS 解析失败（请确认服务器装了 LibreOffice）: " + e.getMessage());
        }
    }

    // ============================================================
    // 统一分发
    // ============================================================
    /** 简单 String 返回（已有调用方兼容）。新接入方建议用 extractPages 拿到带页码的结构化内容 */
    public String extract(InputStream inputStream, String fileType) {
        String ext = fileType.toLowerCase();
        return switch (ext) {
            case "pdf" -> joinPages(extractPdf(inputStream));
            case "docx" -> extractDocx(inputStream);
            case "doc" -> extractDoc(inputStream);
            case "pptx" -> joinPages(extractPptx(inputStream));
            case "ppt" -> joinPages(extractPpt(inputStream));
            case "xlsx", "xls" -> joinPages(extractExcel(inputStream));
            case "csv" -> extractCsv(inputStream);
            case "wps" -> extractWps(inputStream);
            case "html", "htm" -> extractHtml(inputStream);
            case "jpg", "jpeg", "png", "webp", "bmp", "gif" -> extractImage(inputStream, ext);
            case "txt", "md", "markdown" -> extractTxt(inputStream);
            default -> throw new RuntimeException("不支持的文件格式: " + fileType);
        };
    }

    /** 返回带页码的结构化内容（PDF/PPT/Excel 适用，其它格式只返回一项 page=1） */
    public List<PageContent> extractPages(InputStream inputStream, String fileType) {
        String ext = fileType.toLowerCase();
        return switch (ext) {
            case "pdf" -> extractPdf(inputStream);
            case "pptx" -> extractPptx(inputStream);
            case "ppt" -> extractPpt(inputStream);
            case "xlsx", "xls" -> extractExcel(inputStream);
            case "docx" -> List.of(new PageContent(1, extractDocx(inputStream)));
            case "doc" -> List.of(new PageContent(1, extractDoc(inputStream)));
            case "csv" -> List.of(new PageContent(1, extractCsv(inputStream)));
            case "wps" -> List.of(new PageContent(1, extractWps(inputStream)));
            case "html", "htm" -> List.of(new PageContent(1, extractHtml(inputStream)));
            case "jpg", "jpeg", "png", "webp", "bmp", "gif" ->
                    List.of(new PageContent(1, extractImage(inputStream, ext)));
            case "txt", "md", "markdown" -> List.of(new PageContent(1, extractTxt(inputStream)));
            default -> throw new RuntimeException("不支持的文件格式: " + fileType);
        };
    }

    /** 列出当前支持的所有扩展名（用于上传时校验 + 前端 accept 属性生成） */
    public static List<String> supportedExtensions() {
        return List.of(
                "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls", "csv", "wps",
                "html", "htm",
                "jpg", "jpeg", "png", "webp", "bmp", "gif",
                "txt", "md", "markdown"
        );
    }

    private String joinPages(List<PageContent> pages) {
        StringBuilder sb = new StringBuilder();
        for (PageContent p : pages) sb.append(p.text()).append("\n\n");
        return sb.toString();
    }

    /** 页面内容记录 */
    public record PageContent(int pageNumber, String text) {}
}
