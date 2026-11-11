package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.PiiConfig;
import com.simon.MindCrew.mapper.PiiConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PII 脱敏服务 · 任务 12.2
 *
 * 检测类型：
 *   - 中国手机号（11 位 1xxxxxxxxxx）
 *   - 中国身份证号（18 位 / 15 位）
 *   - 银行卡号（13-19 位连续数字）
 *   - 邮箱（默认关）
 *   - 地址 · 需要 LLM 才能精准识别，本版不做正则
 *
 * 脱敏策略：
 *   手机:    138****1234        (保留头 3 尾 4)
 *   身份证:  110101********1234  (保留头 6 尾 4)
 *   银行卡:  6217******1234     (保留头 4 尾 4)
 *   邮箱:    j***@gmail.com     (保留头 1 尾域)
 *
 * 三类应用场景（独立开关）：
 *   apply_on_upload   · 文档入库前（不可逆，谨慎用）
 *   apply_on_response · 问答响应（DB 不动，仅出口脱敏，默认开）
 *   apply_on_audit    · 审计日志 detail_json 写入前
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PiiMaskService {

    private final PiiConfigMapper configMapper;

    private final AtomicReference<PiiConfig> currentConfig = new AtomicReference<>(defaultConfig());

    // 中国手机号 · 11 位 1[3-9]xxxxxxxxx · 前后不接数字（避免误匹配长串）
    // 分组：前 3（138）/ 中 4（脱敏）/ 后 4（保留）
    private static final Pattern PATTERN_PHONE = Pattern.compile(
            "(?<![0-9])(1[3-9]\\d)(\\d{4})(\\d{4})(?![0-9])");

    // 身份证号 · 18 位（地区 6 + 出生 8 + 顺序 3 + 校验 1，末尾可能是 X）
    private static final Pattern PATTERN_ID_CARD = Pattern.compile(
            "(?<![0-9])([1-9]\\d{5})(\\d{8})(\\d{3})([0-9Xx])(?![0-9])");

    // 银行卡号 · 13-19 位（前 4 尾 4 保留），避免误匹配长串数字
    private static final Pattern PATTERN_BANK_CARD = Pattern.compile(
            "(?<![0-9])(\\d{4})(\\d{5,11})(\\d{4})(?![0-9])");

    // 邮箱
    private static final Pattern PATTERN_EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])([A-Za-z0-9])([A-Za-z0-9._%+-]*)(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    @PostConstruct
    public void init() {
        refresh();
    }

    public synchronized void refresh() {
        try {
            PiiConfig c = configMapper.selectById(1L);
            if (c == null) {
                // 表存在但无记录 · 用默认
                c = defaultConfig();
            }
            currentConfig.set(c);
            log.info("[PII] 配置加载 · enabled={} phone={} idCard={} bank={} email={} onUpload={} onResponse={} onAudit={}",
                    c.getEnabled(), c.getMaskPhone(), c.getMaskIdCard(), c.getMaskBankCard(),
                    c.getMaskEmail(), c.getApplyOnUpload(), c.getApplyOnResponse(), c.getApplyOnAudit());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("doesn't exist")) {
                log.warn("[PII] pii_config 表不存在 · 跳过加载 · 请执行 sql/audit-pii-schema.sql");
            } else {
                log.warn("[PII] 加载失败 · 用默认配置: {}", msg);
            }
            currentConfig.set(defaultConfig());
        }
    }

    private static PiiConfig defaultConfig() {
        PiiConfig c = new PiiConfig();
        c.setId(1L);
        c.setEnabled(1);
        c.setMaskPhone(1);
        c.setMaskIdCard(1);
        c.setMaskBankCard(1);
        c.setMaskEmail(0);
        c.setMaskAddress(0);
        c.setApplyOnUpload(0);
        c.setApplyOnResponse(1);
        c.setApplyOnAudit(1);
        return c;
    }

    public PiiConfig getConfig() {
        return currentConfig.get();
    }

    public void updateConfig(PiiConfig patch, Long updatedBy) {
        PiiConfig cur = currentConfig.get();
        if (cur.getId() == null) cur.setId(1L);
        if (patch.getEnabled()        != null) cur.setEnabled(patch.getEnabled());
        if (patch.getMaskPhone()      != null) cur.setMaskPhone(patch.getMaskPhone());
        if (patch.getMaskIdCard()     != null) cur.setMaskIdCard(patch.getMaskIdCard());
        if (patch.getMaskBankCard()   != null) cur.setMaskBankCard(patch.getMaskBankCard());
        if (patch.getMaskEmail()      != null) cur.setMaskEmail(patch.getMaskEmail());
        if (patch.getMaskAddress()    != null) cur.setMaskAddress(patch.getMaskAddress());
        if (patch.getApplyOnUpload()  != null) cur.setApplyOnUpload(patch.getApplyOnUpload());
        if (patch.getApplyOnResponse()!= null) cur.setApplyOnResponse(patch.getApplyOnResponse());
        if (patch.getApplyOnAudit()   != null) cur.setApplyOnAudit(patch.getApplyOnAudit());
        cur.setUpdatedBy(updatedBy);
        try {
            if (configMapper.selectById(cur.getId()) == null) {
                configMapper.insert(cur);
            } else {
                configMapper.updateById(cur);
            }
            currentConfig.set(cur);
            log.info("[PII] 配置已更新 by={}", updatedBy);
        } catch (Exception e) {
            log.warn("[PII] 配置更新失败: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 核心 · 脱敏函数
    // ─────────────────────────────────────────────

    /** 通用脱敏：根据当前全局配置，应用所有启用的规则 */
    public String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        PiiConfig c = currentConfig.get();
        if (c.getEnabled() != 1) return text;

        String out = text;
        if (eq1(c.getMaskPhone()))    out = maskPhone(out);
        if (eq1(c.getMaskIdCard()))   out = maskIdCard(out);
        if (eq1(c.getMaskBankCard())) out = maskBankCard(out);
        if (eq1(c.getMaskEmail()))    out = maskEmail(out);
        return out;
    }

    /** 仅当 apply_on_response 开启时才脱敏（用于问答响应） */
    public String maskForResponse(String text) {
        if (!eq1(currentConfig.get().getApplyOnResponse())) return text;
        return mask(text);
    }

    /** 仅当 apply_on_upload 开启时才脱敏（用于上传入库） */
    public String maskForUpload(String text) {
        if (!eq1(currentConfig.get().getApplyOnUpload())) return text;
        return mask(text);
    }

    /** 仅当 apply_on_audit 开启时才脱敏（用于审计日志 detail_json） */
    public String maskForAudit(String text) {
        if (!eq1(currentConfig.get().getApplyOnAudit())) return text;
        return mask(text);
    }

    public static String maskPhone(String s) {
        if (s == null) return null;
        Matcher m = PATTERN_PHONE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // 138 + **** + 后 4 位
            m.appendReplacement(sb, m.group(1) + "****" + m.group(3));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String maskIdCard(String s) {
        if (s == null) return null;
        Matcher m = PATTERN_ID_CARD.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // 前 6 位 + 出生日期 8 位脱敏 + 顺序后 3 + 校验
            m.appendReplacement(sb, m.group(1) + "********" + m.group(3) + m.group(4));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String maskBankCard(String s) {
        if (s == null) return null;
        Matcher m = PATTERN_BANK_CARD.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // 前 4 + 中间 * + 后 4
            int midLen = m.group(2).length();
            m.appendReplacement(sb, m.group(1) + "*".repeat(midLen) + m.group(3));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String maskEmail(String s) {
        if (s == null) return null;
        Matcher m = PATTERN_EMAIL.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // 首字符保留 + *** + @域
            m.appendReplacement(sb, m.group(1) + "***" + m.group(3));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean eq1(Integer v) { return v != null && v == 1; }
}
