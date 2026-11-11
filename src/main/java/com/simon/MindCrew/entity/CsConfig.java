package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服中心 · 对外页面配置（固定单行 id=1）
 */
@Data
@TableName("cs_config")
public class CsConfig {

    /** 固定单行 id=1 · 表未设 AUTO_INCREMENT，必须用 INPUT 显式写入 */
    @TableId(type = IdType.INPUT)
    private Long id;

    private String pageTitle;
    private String pageSubtitle;
    private String welcomeText;
    private String logoUrl;
    private String themeColor;

    /** JSON 数组字符串 · 快捷问题 */
    private String quickQuestions;

    private String workHours;
    private String offlineTip;

    /** 1=AI 先答 0=直接转人工 */
    private Integer aiEnabled;

    /** JSON 数组字符串 · 命中即转人工 */
    private String handoffKeywords;

    /** JSON 数组字符串 · 客服 AI 检索范围；NULL=全部可见 */
    private String kbIds;

    private Integer ssoEnabled;

    /** internal / external */
    private String ssoMode;

    private String externalCsUrl;
    private Integer satisfactionEnabled;
    private Integer showBrand;
    private String greetingAgentName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
