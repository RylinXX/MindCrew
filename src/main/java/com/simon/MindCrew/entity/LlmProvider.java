package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨厂商 LLM Provider 配置实体。
 *
 * 所有厂商走 OpenAI 兼容协议（DashScope / DeepSeek / OpenAI / Ollama / vLLM / xinference）。
 * 一个 provider 同时定义 chat + embedding 能力；切换 provider 全局生效。
 */
@Data
@TableName("llm_provider")
public class LlmProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 展示名 */
    private String name;

    /** 协议类型，目前都用 openai_compatible */
    private String providerType;

    /** API base URL */
    private String baseUrl;

    /** AES 加密后的 API Key（本地模型可空） */
    private String apiKeyEnc;

    /** 对话模型 */
    private String chatModel;

    /** Embedding 模型（可空，表示该 provider 不提供 embedding） */
    private String embeddingModel;

    /** 向量维度 */
    private Integer embeddingDim;

    /** 默认温度 */
    private BigDecimal temperature;

    /** 备注说明 */
    private String description;

    /** 是否为当前激活 provider（全局唯一） */
    private Integer isActive;

    /** 是否启用 */
    private Integer enabled;

    /** 排序权重 */
    private Integer sortOrder;

    /** 上次测试时间 */
    private LocalDateTime lastTestAt;

    /** 上次测试是否成功 */
    private Integer lastTestOk;

    /** 上次测试消息 */
    private String lastTestMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
