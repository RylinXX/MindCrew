package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型计费配置 · 任务 13
 *
 * chat / vision · 按 token 计费（input + output 分开）
 * embedding · 仅 input
 * asr · 按音频时长（unit_price = 元/秒）
 * rerank / ocr · 按调用次数
 */
@Data
@TableName("model_pricing")
public class ModelPricing {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelName;
    private String category;
    @TableField("input_price_per_1k")
    private BigDecimal inputPricePer1k;
    @TableField("output_price_per_1k")
    private BigDecimal outputPricePer1k;
    private BigDecimal unitPrice;
    private String description;
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
