package com.simon.MindCrew.service.knowledge;

import com.opencsv.CSVReader;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信聊天记录解析器
 *
 * 自动嗅探两种主流导出格式：
 *   - TXT  · WechatExporter / 留痕导出（"发送者  时间\n内容"）
 *   - CSV  · MemoTrace / PCWeChatTool（含 header: sender,time,type,content）
 *
 * 切片策略：按"无消息时长 ≥ 30 分钟"切分 session，每个 session 一个 chunk。
 * 这样既保留对话上下文，又避免单 chunk 过大。
 *
 * 输出 chunk 内容格式（同时利于检索和 LLM 阅读）：
 *   【会话 · 2024-01-15 10:23 ~ 11:05 · 参与者：张三、李四】
 *   张三 [10:23:45]：你好...
 *   李四 [10:25:12]：好的...
 *   ...
 */
@Slf4j
@Component
public class WechatChatParser {

    /** 会话切分阈值（毫秒）：相邻消息间隔超过这个就开新 session */
    private static final long SESSION_GAP_MS = 30L * 60 * 1000;

    /** TXT 模式：发送者 + 多空格 + 时间，跨行（一行发送者+时间，下一行内容） */
    private static final Pattern TXT_HEADER = Pattern.compile(
            "^(?<sender>[^\\s][^\\t]{0,40}?)" +
            "[\\t\\s]+" +
            "(?<time>\\d{4}-\\d{1,2}-\\d{1,2}[\\sT]\\d{1,2}:\\d{1,2}(?::\\d{1,2})?)" +
            "\\s*$"
    );

    private static final DateTimeFormatter[] TIME_FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
    };

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 单条消息 */
    public record Message(String sender, LocalDateTime time, String content) {}

    /** Session = 一段连续对话 */
    public record Session(
            int index,
            LocalDateTime startTime,
            LocalDateTime endTime,
            List<String> participants,
            List<Message> messages
    ) {
        public int messageCount() { return messages.size(); }
    }

    /** 完整解析结果 */
    public record ParseResult(int totalMessages, List<String> allParticipants, List<Session> sessions) {}

    // ─────────────────────────────────────────────
    // 入口
    // ─────────────────────────────────────────────
    public ParseResult parse(InputStream input) {
        byte[] bytes;
        try {
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("微信记录读取失败: " + e.getMessage());
        }
        Charset cs = detectCharset(bytes);
        String text = new String(bytes, cs);

        List<Message> messages;
        if (looksLikeHtml(text)) {
            messages = parseHtml(text);
            // HTML 兜底：结构化解析失败时，把 HTML 转纯文本走 TXT 解析
            if (messages.size() < 2) {
                String plainText = htmlToPlainText(text);
                messages = parseTxt(plainText);
            }
        } else if (looksLikeCsv(text)) {
            messages = parseCsv(bytes, cs);
        } else {
            messages = parseTxt(text);
        }

        // 按时间排序（CSV 通常已有序，但容错）
        messages.sort(Comparator.comparing(Message::time));

        List<Session> sessions = splitIntoSessions(messages);
        Set<String> allP = new LinkedHashSet<>();
        for (Message m : messages) if (m.sender() != null) allP.add(m.sender());

        log.info("[Wechat] 解析完成: {} 条消息, {} 个 session, 参与者 {}",
                messages.size(), sessions.size(), allP);
        return new ParseResult(messages.size(), new ArrayList<>(allP), sessions);
    }

    /** 给定 Session 渲染为可检索的 chunk 文本 */
    public String renderSession(Session s) {
        StringBuilder sb = new StringBuilder();
        sb.append("【会话 · ")
          .append(s.startTime().format(DISPLAY_DATE))
          .append(" ~ ").append(s.endTime().format(DISPLAY_DATE))
          .append(" · 参与者：")
          .append(String.join("、", s.participants()))
          .append("】\n");
        for (Message m : s.messages()) {
            sb.append(m.sender()).append(" [")
              .append(m.time().format(DISPLAY_TIME)).append("]：")
              .append(m.content()).append("\n");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // CSV 解析
    // ─────────────────────────────────────────────
    private boolean looksLikeCsv(String text) {
        // 第一行含 sender + time + content 或 talker + msg 等关键词
        String firstLine = text.lines().findFirst().orElse("").toLowerCase();
        if (!firstLine.contains(",")) return false;
        return (firstLine.contains("sender") || firstLine.contains("talker") || firstLine.contains("发送者"))
                && (firstLine.contains("time") || firstLine.contains("createtime") || firstLine.contains("时间"))
                && (firstLine.contains("content") || firstLine.contains("msg") || firstLine.contains("内容"));
    }

    private List<Message> parseCsv(byte[] bytes, Charset cs) {
        List<Message> result = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(new ByteArrayInputStream(bytes), cs))) {
            String[] header = reader.readNext();
            if (header == null) return result;

            int senderIdx = findColumn(header, "sender", "talker", "发送者", "from");
            int timeIdx   = findColumn(header, "time", "createtime", "时间", "datetime");
            int typeIdx   = findColumn(header, "type", "类型", "msgtype");
            int contentIdx = findColumn(header, "content", "msg", "内容", "message");

            if (senderIdx < 0 || timeIdx < 0 || contentIdx < 0) {
                log.warn("[Wechat] CSV 缺关键列，header={}", Arrays.toString(header));
                return result;
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length <= Math.max(timeIdx, contentIdx)) continue;
                String sender = safe(row, senderIdx).trim();
                String content = safe(row, contentIdx).trim();
                if (content.isEmpty()) continue;

                // 类型识别：跳过系统消息 / 非文本
                if (typeIdx >= 0) {
                    String t = safe(row, typeIdx).trim().toLowerCase();
                    if (!t.isEmpty() && !t.equals("text") && !t.equals("1") && !t.equals("文本")) {
                        // 系统消息 / 图片 / 文件 等用占位符表示
                        content = "[" + t + "] " + content;
                    }
                }

                LocalDateTime time = parseTime(safe(row, timeIdx));
                if (time == null || sender.isEmpty()) continue;

                result.add(new Message(sender, time, content));
            }
        } catch (Exception e) {
            log.error("[Wechat] CSV 解析失败", e);
        }
        return result;
    }

    private int findColumn(String[] header, String... candidates) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i].toLowerCase().trim();
            for (String c : candidates) {
                if (h.equals(c.toLowerCase())) return i;
            }
        }
        return -1;
    }

    private String safe(String[] row, int idx) {
        return (idx >= 0 && idx < row.length && row[idx] != null) ? row[idx] : "";
    }

    // ─────────────────────────────────────────────
    // HTML 解析
    // 主流微信导出工具（WechatExporter / 留痕 / 微信备份大师）的 HTML 结构差异较大，
    // 采用"宽松适配 + 多 selector 尝试 + 兜底转纯文本"三层策略
    // ─────────────────────────────────────────────
    private boolean looksLikeHtml(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        if (t.length() < 30) return false;
        return t.startsWith("<!doctype")
                || t.startsWith("<html")
                || (t.startsWith("<") && (t.contains("<body") || t.contains("<div")));
    }

    private List<Message> parseHtml(String html) {
        List<Message> result = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);

            // 兼容多种导出结构的 selector（优先级从严到松）
            String[] containerSelectors = {
                    "div.message", "li.message", "div.msg", "li.msg",
                    "div.chat-item", "div.msg-item", "div.record",
                    "div[data-time]", "div[data-sender]", "div[data-talker]"
            };

            Elements containers = null;
            for (String sel : containerSelectors) {
                Elements els = doc.select(sel);
                if (els.size() >= 2) { containers = els; break; }
            }

            if (containers == null) {
                // 无明确容器结构 → 返回空让上层走纯文本兜底
                return result;
            }

            for (Element c : containers) {
                String sender = pickText(c,
                        "[data-sender]@data-sender",
                        "[data-talker]@data-talker",
                        ".sender", ".name", ".talker", ".from", ".nickname");
                String timeStr = pickText(c,
                        "[data-time]@data-time",
                        ".time", ".date", ".datetime", ".timestamp", ".created");
                String content = pickText(c,
                        ".content", ".msg", ".message-content", ".text", ".body");
                if (content == null || content.isBlank()) {
                    // 没有专门的内容容器 → 取整个元素的剩余文本
                    String full = c.text();
                    if (timeStr != null) full = full.replace(timeStr, "");
                    if (sender != null) full = full.replace(sender, "");
                    content = full.trim();
                }

                LocalDateTime time = parseTime(timeStr);
                if (time == null || sender == null || sender.isBlank() || content == null || content.isBlank()) continue;
                result.add(new Message(sender.trim(), time, content.trim()));
            }
        } catch (Exception e) {
            log.warn("[Wechat] HTML 结构化解析失败: {}", e.getMessage());
        }
        return result;
    }

    /** 按选择器列表取首个非空值。selector 支持 "@attr" 后缀表示取属性 */
    private String pickText(Element root, String... selectors) {
        for (String raw : selectors) {
            String sel = raw;
            String attr = null;
            int at = raw.indexOf('@');
            if (at > 0) {
                sel = raw.substring(0, at);
                attr = raw.substring(at + 1);
            }
            Element e = root.selectFirst(sel);
            if (e == null) continue;
            String v = (attr != null) ? e.attr(attr) : e.text();
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /** HTML → 纯文本（保留段落换行）。给纯文本 fallback 用。 */
    private String htmlToPlainText(String html) {
        try {
            Document doc = Jsoup.parse(html);
            // 用 \n 替换块级元素结尾，保留视觉换行
            doc.select("br").append("\n");
            doc.select("p, div, li, tr").append("\n");
            String text = doc.text(); // jsoup 的 text() 会规整化空白
            // 上面 append 出来的 \n 会被 text() 吞掉，改用 wholeText
            text = doc.wholeText();
            return text.replaceAll("\\n{3,}", "\n\n");
        } catch (Exception e) {
            return html.replaceAll("<[^>]+>", " ");  // 粗暴兜底
        }
    }

    // ─────────────────────────────────────────────
    // TXT 解析
    // ─────────────────────────────────────────────
    private List<Message> parseTxt(String text) {
        List<Message> result = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        String curSender = null;
        LocalDateTime curTime = null;
        StringBuilder curContent = new StringBuilder();

        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                // 空行 = 当前消息结束
                flushCurrent(result, curSender, curTime, curContent);
                curSender = null; curTime = null;
                curContent.setLength(0);
                continue;
            }
            Matcher m = TXT_HEADER.matcher(line);
            if (m.find()) {
                // 新消息头：先 flush 旧的
                flushCurrent(result, curSender, curTime, curContent);
                curSender = m.group("sender").trim();
                curTime = parseTime(m.group("time").replace('T', ' '));
                curContent.setLength(0);
            } else if (curSender != null) {
                if (curContent.length() > 0) curContent.append("\n");
                curContent.append(line);
            }
            // 没匹配到头 + 没在累积内容 → 跳过（可能是文件开头杂项）
        }
        flushCurrent(result, curSender, curTime, curContent);
        return result;
    }

    private void flushCurrent(List<Message> result, String sender, LocalDateTime time, StringBuilder content) {
        if (sender == null || time == null) return;
        String c = content.toString().trim();
        if (c.isEmpty()) return;
        result.add(new Message(sender, time, c));
    }

    // ─────────────────────────────────────────────
    // 时间解析（多格式尝试）
    // ─────────────────────────────────────────────
    private LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().replace('T', ' ');
        // 补齐 yyyy-M-d H:m 为 yyyy-MM-dd HH:mm:ss
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ─────────────────────────────────────────────
    // Session 切分
    // ─────────────────────────────────────────────
    private List<Session> splitIntoSessions(List<Message> messages) {
        List<Session> result = new ArrayList<>();
        if (messages.isEmpty()) return result;

        List<Message> current = new ArrayList<>();
        LocalDateTime lastTime = null;
        int idx = 0;

        for (Message m : messages) {
            if (lastTime != null) {
                long gap = java.time.Duration.between(lastTime, m.time()).toMillis();
                if (gap >= SESSION_GAP_MS && !current.isEmpty()) {
                    result.add(buildSession(++idx, current));
                    current = new ArrayList<>();
                }
            }
            current.add(m);
            lastTime = m.time();
        }
        if (!current.isEmpty()) result.add(buildSession(++idx, current));
        return result;
    }

    private Session buildSession(int index, List<Message> msgs) {
        Set<String> p = new LinkedHashSet<>();
        for (Message m : msgs) if (m.sender() != null) p.add(m.sender());
        return new Session(
                index,
                msgs.get(0).time(),
                msgs.get(msgs.size() - 1).time(),
                new ArrayList<>(p),
                msgs
        );
    }

    // ─────────────────────────────────────────────
    // 编码嗅探（UTF-8 / GBK 兜底）
    // ─────────────────────────────────────────────
    private Charset detectCharset(byte[] bytes) {
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

    /** 支持的扩展名（用于上传校验）。
     *  TXT/CSV/HTML 三种都可能是微信导出，由 DocumentProcessTask 嗅探后决定是否走此解析。 */
    public static List<String> supportedExtensions() {
        return List.of("txt", "csv", "html", "htm");
    }
}
