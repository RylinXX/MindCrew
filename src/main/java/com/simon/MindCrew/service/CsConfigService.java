package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.CsConfig;
import com.simon.MindCrew.mapper.CsConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客服中心 · 页面配置读写
 *
 * 单行配置表 cs_config（id=1）。首版直接查库（契约允许不做缓存），
 * 保证「配置改动后公开页立即生效（无需重启）」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsConfigService {

    /** 固定单行 id */
    public static final long CONFIG_ID = 1L;

    /** 工作时间解析 · 支持 9:00-18:00 / 09:00~18:00 */
    private static final Pattern WORK_HOURS_PATTERN =
            Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[-~—]\\s*(\\d{1,2}):(\\d{2})");

    private final CsConfigMapper configMapper;

    /** 读取配置，缺失时落默认单行 */
    public CsConfig getEntity() {
        CsConfig cfg = configMapper.selectById(CONFIG_ID);
        if (cfg == null) {
            cfg = defaultConfig();
            configMapper.insert(cfg);
            log.info("[CS] cs_config 缺失，已写入默认配置 id=1");
        }
        return cfg;
    }

    /** 管理端全量配置 */
    public CsConfig getFull() {
        return getEntity();
    }

    /** 保存配置（固定 id=1） */
    public CsConfig save(CsConfig cfg) {
        if (cfg == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "配置不能为空");
        }
        cfg.setId(CONFIG_ID);
        cfg.setCreateTime(null);
        if (cfg.getThemeColor() == null || cfg.getThemeColor().isBlank()) {
            cfg.setThemeColor("#3D5AFE");
        }
        if (cfg.getSsoMode() == null || cfg.getSsoMode().isBlank()) {
            cfg.setSsoMode("internal");
        }
        CsConfig existing = configMapper.selectById(CONFIG_ID);
        if (existing == null) {
            configMapper.insert(cfg);
        } else {
            configMapper.updateById(cfg);
        }
        return getEntity();
    }

    /**
     * 公开配置视图（契约 4.1·GET /config）：
     * 不暴露 kbIds / handoffKeywords 等内部字段。
     */
    public Map<String, Object> publicView() {
        CsConfig c = getEntity();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pageTitle", c.getPageTitle());
        m.put("pageSubtitle", c.getPageSubtitle());
        m.put("welcomeText", c.getWelcomeText());
        m.put("logoUrl", c.getLogoUrl());
        m.put("themeColor", c.getThemeColor());
        m.put("quickQuestions", parseStringList(c.getQuickQuestions()));
        m.put("workHours", c.getWorkHours());
        m.put("offlineTip", c.getOfflineTip());
        m.put("aiEnabled", c.getAiEnabled());
        m.put("satisfactionEnabled", c.getSatisfactionEnabled());
        m.put("showBrand", c.getShowBrand());
        m.put("greetingAgentName", c.getGreetingAgentName());
        m.put("ssoEnabled", c.getSsoEnabled());
        m.put("ssoMode", c.getSsoMode());
        m.put("online", isOnline(c));
        return m;
    }

    /** 首屏欢迎语（兜底用 AI 坐席名） */
    public String welcomeText(CsConfig c) {
        if (c == null) c = getEntity();
        if (c.getWelcomeText() != null && !c.getWelcomeText().isBlank()) {
            return c.getWelcomeText();
        }
        String agent = c.getGreetingAgentName() == null ? "智能客服" : c.getGreetingAgentName();
        return "你好，我是" + agent + "，有什么可以帮你？";
    }

    /** quick_questions → 字符串列表 */
    public List<String> quickQuestions(CsConfig c) {
        return parseStringList(c == null ? null : c.getQuickQuestions());
    }

    /** kb_ids → Long 列表（NULL / 空数组 = 不过滤，全部可见） */
    public List<Long> kbIds(CsConfig c) {
        if (c == null || c.getKbIds() == null || c.getKbIds().isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Long> list = JSON.parseArray(c.getKbIds(), Long.class);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("[CS] cs_config.kb_ids 解析失败: {}", c.getKbIds());
            return new ArrayList<>();
        }
    }

    /** handoff_keywords → 字符串列表 */
    public List<String> handoffKeywords(CsConfig c) {
        return parseStringList(c == null ? null : c.getHandoffKeywords());
    }

    /** 命中转人工关键词（包含匹配） */
    public boolean hitHandoffKeyword(String content, CsConfig c) {
        if (content == null || content.isBlank()) return false;
        for (String keyword : handoffKeywords(c)) {
            if (keyword != null && !keyword.isBlank() && content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 是否处于工作时间；解析失败按在线处理 */
    public boolean isOnline(CsConfig c) {
        if (c == null) return true;
        String workHours = c.getWorkHours();
        if (workHours == null || workHours.isBlank()) return true;
        try {
            Matcher m = WORK_HOURS_PATTERN.matcher(workHours);
            if (!m.find()) return true;
            LocalTime start = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            LocalTime end = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            LocalTime now = LocalTime.now();
            if (start.equals(end)) return true;
            if (start.isBefore(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            }
            // 跨夜工作时间
            return !now.isBefore(start) || !now.isAfter(end);
        } catch (Exception e) {
            return true;
        }
    }

    /** JSON 字符串数组解析；非法 JSON 返回空列表，不抛异常 */
    public static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> list = JSON.parseArray(json, String.class);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private CsConfig defaultConfig() {
        CsConfig c = new CsConfig();
        c.setId(CONFIG_ID);
        c.setPageTitle("MindCrew 智能客服");
        c.setPageSubtitle(null);
        c.setWelcomeText("你好，我是智能客服小梦，有什么可以帮你？");
        c.setThemeColor("#3D5AFE");
        c.setQuickQuestions(JSON.toJSONString(List.of("怎么配置权限？", "如何上传知识库？", "支持哪些文件格式？")));
        c.setWorkHours("9:00-18:00");
        c.setOfflineTip("当前非工作时间，已为你转接人工客服，请留言。");
        c.setAiEnabled(1);
        c.setHandoffKeywords(JSON.toJSONString(List.of("转人工", "人工客服")));
        c.setSsoEnabled(0);
        c.setSsoMode("internal");
        c.setSatisfactionEnabled(1);
        c.setShowBrand(1);
        c.setGreetingAgentName("小梦");
        return c;
    }
}
