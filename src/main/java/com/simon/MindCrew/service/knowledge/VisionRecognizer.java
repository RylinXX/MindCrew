package com.simon.MindCrew.service.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;

/**
 * 视觉识别器 · 基于通义千问 VL（qwen-vl-max）。
 *
 * 单次调用同时返回：
 *   1. OCR 文字提取（图片中的所有可识别文字）
 *   2. 视觉语义描述（这张图说的是什么）
 *
 * 走 OpenAI Compatible 协议复用现有 LLM 配置（DashScope）。
 * 切换到其他厂商（OpenAI gpt-4o / Claude 3.7）只需改 base-url + model。
 *
 * 配置 application.yml:
 *   llm:
 *     base-url: https://dashscope.aliyuncs.com/compatible-mode
 *     api-key: ${BAILIAN_API_KEY}
 *   vision:
 *     model: qwen-vl-max     # 或 qwen-vl-plus（便宜版）
 *     max-tokens: 2000
 *     timeout-seconds: 60
 */
@Slf4j
@Component
public class VisionRecognizer {

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${vision.model:qwen-vl-max}")
    private String model;

    @Value("${vision.max-tokens:2000}")
    private int maxTokens;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String SYSTEM_PROMPT = """
            你是一个图片识别助手。对收到的图片输出两段内容，用三个英文减号 --- 严格分隔：

            第一段 · OCR：完整提取图片中所有可见文字（中英文、数字、标点都要保留）。
            按从上到下、从左到右的视觉顺序排列。如果图片完全没有文字，写 "无文字"。

            ---

            第二段 · 描述：用 50-100 字客观描述这张图片是什么（截图/照片/图表/手写笔记/产品图/扫描件...），
            主要内容是什么，关键视觉元素有哪些。不要猜测和主观评价。

            禁止任何 emoji、装饰符号、营销话术。
            """;

    /**
     * 识别一张图片。
     *
     * @param imageBytes 图片二进制
     * @param mimeType   MIME，如 image/jpeg / image/png
     * @return 识别结果，含 ocrText（提取的文字）和 description（视觉描述）
     */
    /** 是否配置了可用的视觉 Key（DashScope qwen-vl） */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"mock-key".equalsIgnoreCase(apiKey);
    }

    public VisionResult recognize(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        JSONObject content1 = new JSONObject();
        content1.put("type", "text");
        content1.put("text", "请按系统指令处理这张图片。");

        JSONObject imgUrl = new JSONObject();
        imgUrl.put("url", dataUrl);
        JSONObject content2 = new JSONObject();
        content2.put("type", "image_url");
        content2.put("image_url", imgUrl);

        JSONArray contents = new JSONArray();
        contents.add(content1);
        contents.add(content2);

        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", contents);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", JSONArray.of(systemMsg, userMsg));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body.toJSONString(), headers), String.class);
            return parseResult(resp.getBody());
        } catch (Exception e) {
            log.error("[VisionRecognizer] 调用失败: {}", e.getMessage());
            return new VisionResult("", "图片识别失败：" + e.getMessage(), false);
        }
    }

    private VisionResult parseResult(String body) {
        if (body == null || body.isBlank()) {
            return new VisionResult("", "", false);
        }
        try {
            JSONObject obj = JSON.parseObject(body);
            String content = obj.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            if (content == null) return new VisionResult("", "", false);

            // 按 --- 拆两段
            String[] parts = content.split("(?m)^\\s*---\\s*$", 2);
            String ocr = parts.length >= 1 ? parts[0].trim() : "";
            String desc = parts.length >= 2 ? parts[1].trim() : "";

            // 清理可能的前缀
            ocr = stripLeadingLabel(ocr, "OCR", "OCR：", "第一段", "第一段·OCR：");
            desc = stripLeadingLabel(desc, "描述", "描述：", "第二段", "第二段·描述：");

            if ("无文字".equals(ocr)) ocr = "";

            return new VisionResult(ocr, desc, true);
        } catch (Exception e) {
            log.warn("[VisionRecognizer] 响应解析失败，原文: {}", body);
            // 兜底：把整个 content 当 OCR 文字
            return new VisionResult(body.length() > 1500 ? body.substring(0, 1500) : body, "", false);
        }
    }

    private String stripLeadingLabel(String text, String... labels) {
        if (text == null) return "";
        String t = text.trim();
        for (String label : labels) {
            if (t.startsWith(label)) {
                t = t.substring(label.length()).trim();
                if (t.startsWith("：") || t.startsWith(":")) t = t.substring(1).trim();
            }
        }
        return t;
    }

    /** 视觉识别结果 */
    public record VisionResult(String ocrText, String description, boolean success) {
        /** 合并为入库用的单段文本，含 OCR + 描述，方便检索 */
        public String toIndexedText() {
            StringBuilder sb = new StringBuilder();
            if (description != null && !description.isBlank()) {
                sb.append("【图片描述】").append(description).append("\n\n");
            }
            if (ocrText != null && !ocrText.isBlank()) {
                sb.append("【图片文字】\n").append(ocrText);
            }
            return sb.toString().trim();
        }
    }

    /** 当前支持的图片格式 */
    public static List<String> supportedExtensions() {
        return List.of("jpg", "jpeg", "png", "webp", "bmp", "gif");
    }

    public static String mimeOf(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
    }
}
