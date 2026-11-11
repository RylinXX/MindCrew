package com.simon.MindCrew.service.office;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数字员工 · PPTX / DOCX / 合同真实生成冒烟测试（生成后用 POI 重新打开校验）
 */
class OfficeDocumentServiceTest {

    private final OfficeDocumentService service =
            new OfficeDocumentService(new MarkdownParser(), new ContractTemplateRegistry());

    private static final String MD = """
            # 年度总结
            ## 第一节 背景
            - 要点一
            - 要点二 **加粗**
            ### 子标题
            - 子项 A
            ## 第二节 方案
            普通段落内容，用于验证正文排版与中文显示。
            ## 第三节 结论
            - 结论一
            """;

    @Test
    void generatesOpenablePptx() throws Exception {
        byte[] bytes = service.toPptx("MindCrew 测试报告", "自动生成的副标题", MD);
        assertTrue(bytes.length > 5000, "pptx 体积过小，可能生成失败");
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertTrue(ppt.getSlides().size() >= 4, "应包含封面/目录/章节页");
        }
    }

    @Test
    void generatesOpenableDocx() throws Exception {
        byte[] bytes = service.toDocx("MindCrew 测试报告", MD);
        assertTrue(bytes.length > 1000);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertTrue(doc.getParagraphs().size() >= 5);
            assertNotNull(doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT));
        }
    }

    @Test
    void generatesOpenableContractDocx() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("partyA", "甲方公司");
        fields.put("partyB", "乙方公司");
        fields.put("productName", "知识库系统");
        fields.put("amount", "100000");

        byte[] bytes = service.toContractDocx("purchase", fields);
        assertTrue(bytes.length > 1000);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertTrue(doc.getParagraphs().size() > 3);
            assertTrue(doc.getParagraphs().stream()
                    .anyMatch(p -> p.getText() != null && p.getText().contains("采购合同")));
        }
    }

    @Test
    void emptyMarkdownStillProducesCoverOnlyDeck() throws Exception {
        byte[] bytes = service.toPptx("空内容测试", null, "");
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertTrue(ppt.getSlides().size() >= 1);
        }
    }
}
