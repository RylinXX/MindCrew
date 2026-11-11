package com.simon.MindCrew.service.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本噪声清洗器。
 *
 * 典型场景：招聘网站导出的简历 PDF 带有隐藏水印（一长串随机字母数字 + ~~ 分隔、
 * 或按字符拆成一行行的短片段）。PDFBox 提取时这些水印会混进正文，污染切片与检索。
 *
 * 策略（宁可少删，不误伤正文）：
 *   1. 先判断文本是否“疑似带水印”：随机 token ≥2 次 / 噪声行 ≥4 行 / 同一 ASCII 行重复 ≥3 次；
 *   2. 疑似带水印时才清洗：
 *      - 删除「纯 ASCII、无空格、非白名单」的噪声行（含数字+字母、含 ~、纯十六进制、重复行等）；
 *      - 删除正文中夹杂的长随机 token（8 位以上的字母数字混合、10 位以上 hex、含 ~ 的串）；
 *   3. 纯英文单词（SpringBoot、Java 等常见技术词）与邮箱/URL 不处理。
 */
@Slf4j
@Component
public class TextNoiseCleaner {

    /** 行内候选 token（无空格 ASCII 串） */
    private static final Pattern ASCII_LINE = Pattern.compile("^[A-Za-z0-9_~\\-]{2,120}$");

    /** 正文中的长随机 token：字母数字混合 ≥8 位 / 纯 hex ≥10 位 / 含 ~ */
    private static final Pattern RANDOM_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_~\\-])(?:(?=[A-Za-z0-9_~\\-]*\\d)(?=[A-Za-z0-9_~\\-]*[A-Za-z])[A-Za-z0-9_~\\-]{8,}"
            + "|[0-9a-fA-F]{10,}"
            + "|[A-Za-z0-9_~\\-]*~[A-Za-z0-9_~\\-]*)(?![A-Za-z0-9_~\\-])");

    /** 正文中的短随机字母 token（无数字）：长度 5~12、大小写混杂、元音比例极低，如 OGmnPPW */
    private static final Pattern RANDOM_LETTER_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_~\\-])(?=[A-Za-z]*[A-Z])(?=[A-Za-z]*[a-z])[A-Za-z]{5,12}(?![A-Za-z0-9_~\\-])");

    /** 已知招聘网站水印长片段（单独出现也删除，正常简历不会出现这种串） */
    private static final Pattern KNOWN_WATERMARK_TOKEN = Pattern.compile(
            "OGmnPPW|MBRr1g~{0,2}|f5256d7525c5|651HNz3|dq7GFVW|m3U_6cW|c9651HNz3");

    /** 已知招聘网站水印片段（连续出现 ≥2 段才删，避免误伤正常单词） */
    private static final Pattern KNOWN_WATERMARK_RUN = Pattern.compile(
            "(?:(?:f5256d7525c5|c9651HNz3|651HNz3|dq7GFVW|m3U_6cW|OGmnPPW|MBRr1g~{0,2}|xIi|-U|XW|c9|xo|f5|25|6d|75|c5)\\s*){2,}");

    /** 常见技术词 / 缩写，避免误删 */
    private static final Set<String> WHITELIST = new HashSet<>(Arrays.asList(
            "java","javascript","typescript","python","spring","springboot","springcloud","mysql","redis",
            "mongodb","docker","linux","vue","react","angular","node","html","css","sql","git","ai","ml",
            "nlp","llm","rag","api","http","https","json","xml","go","php","ruby","scala","kotlin","swift",
            "flutter","android","ios","excel","word","ppt","ps","pr","ae","figma","sketch","axure","xmind",
            "oracle","sap","erp","crm","saas","paas","iaas","k8s","devops","ci","cd","ui","ux","bi","etl",
            "c++","c#",".net","jvm","gc","jdk","jre","mvc","orm","rpc","mq","kafka","rabbitmq","zookeeper",
            "nginx","tomcat","apache","hadoop","spark","flink","clickhouse","elasticsearch","lucene","milvus"
    ));

    /** 简历专用：在通用清洗后再清掉水印的短碎片行（xo / XW / xIi / 单字母 / 数字碎片等） */
    public String cleanResume(String text) {
        String cleaned = clean(text);
        if (cleaned == null || cleaned.isBlank()) return cleaned;

        StringBuilder sb = new StringBuilder(cleaned.length());
        for (String raw : cleaned.split("\n", -1)) {
            String line = raw.trim();
            if (isResumeFragmentLine(line)) continue;
            sb.append(raw).append('\n');
        }
        String result = sb.toString();
        // 正文中零星残留的已知短碎片（按词边界，避免误伤正常单词）
        result = result.replaceAll("(?<![A-Za-z0-9])(?:xo|c9|XW|xIi|-U)(?![A-Za-z0-9])", " ");
        result = result.replaceAll("[ \t]{2,}", " ").replaceAll("\n{3,}", "\n\n").trim();
        return result;
    }

    private boolean isResumeFragmentLine(String line) {
        if (line.isEmpty()) return false;
        if (line.matches("^~+$")) return true;
        if (line.matches("^-[A-Za-z]{1,3}$")) return true;
        if (line.matches("^[A-Za-z]{1,4}$")) {
            return !WHITELIST.contains(line.toLowerCase(Locale.ROOT));
        }
        if (line.matches("^[A-Za-z0-9]{2,3}$")) {
            String lower = line.toLowerCase(Locale.ROOT);
            return !WHITELIST.contains(lower) && !lower.matches("^(5g|4g|3d|2d|vr|ar|mr)$");
        }
        return false;
    }

    /** 清洗文本；无噪声时原样返回 */
    public String clean(String text) {
        if (text == null || text.isBlank()) return text;
        // 招聘水印有固定字符组合，先剔除已知片段，再走通用规则
        text = KNOWN_WATERMARK_TOKEN.matcher(text).replaceAll(" ");
        text = KNOWN_WATERMARK_RUN.matcher(text).replaceAll(" ");

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Map<String, Integer> asciiFreq = new HashMap<>();
        int noiseLineCandidates = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (isAsciiNoiseLine(line)) {
                noiseLineCandidates++;
                asciiFreq.merge(line.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        int randomTokenCount = countMatches(RANDOM_TOKEN, text);
        boolean repeatedAsciiLine = asciiFreq.values().stream().anyMatch(v -> v >= 2);
        int maxAsciiRun = maxConsecutiveAsciiLines(lines);
        boolean watermarkPresent = randomTokenCount >= 2 || noiseLineCandidates >= 3
                || repeatedAsciiLine || maxAsciiRun >= 2;

        if (!watermarkPresent) return text;

        StringBuilder sb = new StringBuilder(text.length());
        int removedLines = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (isAsciiNoiseLine(line) && shouldRemoveLine(line, asciiFreq)) {
                removedLines++;
                continue;
            }
            sb.append(raw).append('\n');
        }

        String result = sb.toString();
        result = removeInlineNoise(result);
        result = result.replaceAll("(?m)^[ \\t]+$", "");
        result = result.replaceAll("\\n{3,}", "\n\n");
        result = result.replaceAll("[ \\t]{2,}", " ").trim();

        if (removedLines > 0 || randomTokenCount > 0) {
            log.info("[NoiseCleaner] 清洗水印：删除噪声行 {}，随机 token {}，长度 {} → {}",
                    removedLines, randomTokenCount, text.length(), result.length());
        }
        return result;
    }

    private boolean isAsciiNoiseLine(String line) {
        if (line.length() < 2 || line.length() > 120) return false;
        if (!ASCII_LINE.matcher(line).matches()) return false;
        // 邮箱 / URL / 时间等一律不当作噪声行
        if (line.contains("@") || line.contains("/") || line.contains(":") || line.contains(".")) return false;
        return true;
    }

    private boolean shouldRemoveLine(String line, Map<String, Integer> freq) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (WHITELIST.contains(lower)) return false;
        boolean hasDigit = line.matches(".*\\d.*");
        boolean hasLetter = line.matches(".*[A-Za-z].*");
        boolean repeated = freq.getOrDefault(lower, 0) >= 2;
        boolean hex = line.matches("[0-9a-fA-F]{6,}");
        boolean tilde = line.contains("~");
        // 有数字+字母的随机串、纯 hex、含 ~、重复出现的片段 → 水印
        if ((hasDigit && hasLetter) || hex || tilde || repeated) return true;

        // 短碎片（2~3 位）：含连字符、无元音（如 XW / -U / c9）在简历水印里很常见
        if (line.length() <= 3) {
            return line.contains("-") || !hasVowel(line);
        }
        // 长度 >=5 且大小写混杂、元音比例极低的随机串（如 OGmnPPW）
        if (line.length() >= 5 && hasLetter && line.matches(".*[A-Z].*") && line.matches(".*[a-z].*")) {
            double vowelRatio = (double) countVowels(line) / line.length();
            if (vowelRatio < 0.25) return true;
        }
        return false;
    }

    private String removeInlineNoise(String text) {
        int removed = 0;
        Matcher m = RANDOM_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = m.group();
            if (WHITELIST.contains(token.toLowerCase(Locale.ROOT))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(token));
            } else {
                m.appendReplacement(sb, " ");
                removed++;
            }
        }
        m.appendTail(sb);
        String result = sb.toString();

        // 第二类：无数字的短随机字母串（OGmnPPW 这类），按元音比例判断
        Matcher lm = RANDOM_LETTER_TOKEN.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (lm.find()) {
            String token = lm.group();
            String lower = token.toLowerCase(Locale.ROOT);
            boolean keep = WHITELIST.contains(lower) || ((double) countVowels(token) / token.length()) >= 0.25;
            if (keep) {
                lm.appendReplacement(sb2, Matcher.quoteReplacement(token));
            } else {
                lm.appendReplacement(sb2, " ");
                removed++;
            }
        }
        lm.appendTail(sb2);
        return removed > 0 ? sb2.toString() : text;
    }

    private int maxConsecutiveAsciiLines(String[] lines) {
        int max = 0, cur = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (isAsciiNoiseLine(line)) {
                cur++;
                max = Math.max(max, cur);
            } else if (!line.isEmpty()) {
                cur = 0;
            }
        }
        return max;
    }

    private boolean hasVowel(String s) {
        for (char c : s.toCharArray()) {
            if ("aeiouAEIOU".indexOf(c) >= 0) return true;
        }
        return false;
    }

    private int countVowels(String s) {
        int c = 0;
        for (char ch : s.toCharArray()) {
            if ("aeiouAEIOU".indexOf(ch) >= 0) c++;
        }
        return c;
    }

    private int countMatches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int c = 0;
        while (m.find()) c++;
        return c;
    }
}
