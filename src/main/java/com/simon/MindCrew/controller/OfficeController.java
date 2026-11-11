package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.service.office.ContractTemplateRegistry;
import com.simon.MindCrew.service.office.OfficeDocumentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数字员工 · Office 文档生成接口
 *
 *   GET  /api/v2/office/templates   内置合同模板列表
 *   POST /api/v2/office/pptx        Markdown → PPTX
 *   POST /api/v2/office/docx        Markdown → DOCX
 *   POST /api/v2/office/contract    合同字段 → DOCX
 *
 * 鉴权：登录即可（SecurityConfig 的 anyRequest().authenticated()）。
 */
@RestController
@RequestMapping("/api/v2/office")
@RequiredArgsConstructor
public class OfficeController {

    private final OfficeDocumentService officeDocumentService;
    private final ContractTemplateRegistry contractTemplateRegistry;

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern ILLEGAL_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    @GetMapping("/templates")
    public Result<List<Map<String, Object>>> templates() {
        return Result.success(contractTemplateRegistry.templateViews());
    }

    @PostMapping("/pptx")
    public ResponseEntity<byte[]> generatePptx(@RequestBody PptxRequest request) {
        requireText(request.getTitle(), "title");
        requireText(request.getMarkdown(), "markdown");
        byte[] data = officeDocumentService.toPptx(request.getTitle(), request.getSubtitle(), request.getMarkdown());
        String filename = safeName(request.getTitle(), "MindCrew-文档") + "-"
                + LocalDateTime.now().format(FILE_TIME) + ".pptx";
        return attachment(data, filename, OfficeDocumentService.CONTENT_TYPE_PPTX);
    }

    @PostMapping("/docx")
    public ResponseEntity<byte[]> generateDocx(@RequestBody DocxRequest request) {
        requireText(request.getTitle(), "title");
        requireText(request.getMarkdown(), "markdown");
        byte[] data = officeDocumentService.toDocx(request.getTitle(), request.getMarkdown());
        String filename = safeName(request.getTitle(), "MindCrew-文档") + "-"
                + LocalDateTime.now().format(FILE_TIME) + ".docx";
        return attachment(data, filename, OfficeDocumentService.CONTENT_TYPE_DOCX);
    }

    @PostMapping("/contract")
    public ResponseEntity<byte[]> generateContract(@RequestBody ContractRequest request) {
        if (request.getTemplate() == null || request.getTemplate().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "template 不能为空");
        }
        // 先取模板名（不支持的模板会在这里 400）
        String templateName = contractTemplateRegistry.get(request.getTemplate()).name();
        Map<String, Object> fields = request.getFields() == null
                ? Map.<String, Object>of() : request.getFields();
        byte[] data = officeDocumentService.toContractDocx(request.getTemplate(), fields);

        String partyB = fields.get("partyB") == null ? "" : String.valueOf(fields.get("partyB"));
        String filename = templateName + "-" + safeName(partyB, "未命名") + "-"
                + LocalDate.now().format(FILE_DATE) + ".docx";
        return attachment(data, filename, OfficeDocumentService.CONTENT_TYPE_DOCX);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private ResponseEntity<byte[]> attachment(byte[] data, String filename, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(data);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), field + " 不能为空");
        }
    }

    private String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String cleaned = ILLEGAL_FILENAME.matcher(value.trim()).replaceAll("");
        if (cleaned.isBlank()) return fallback;
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    // ============================================================
    // 请求体
    // ============================================================

    @Data
    public static class PptxRequest {
        private String title;
        private String subtitle;
        private String markdown;
    }

    @Data
    public static class DocxRequest {
        private String title;
        private String markdown;
    }

    @Data
    public static class ContractRequest {
        private String template;
        private Map<String, Object> fields;
    }
}
