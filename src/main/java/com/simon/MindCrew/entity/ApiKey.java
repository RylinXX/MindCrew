package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 对外 API Key · 任务 11
 *
 * 字段说明：
 *   key_prefix  · 前缀 mk_xxxxxxxx，用于列表展示（永远不存明文）
 *   key_hash    · 完整 key 的 SHA-256，用于校验（不可逆）
 *   allowed_kb_ids · JSON 数组（11.6 · 一个 key 至少绑 1 个 KB）
 *
 * 安全考虑（铁律 · 务实零 mock）：
 *   - 完整 key 仅在生成那一刻通过 API 返回给前端**一次**
 *   - DB 只存 prefix + SHA-256 hash
 *   - 后端校验时把传入 key 做 SHA-256 后查 hash
 *   - 即便 DB 泄露，攻击者也无法逆推完整 key
 */
@Data
@TableName("api_key")
public class ApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String keyPrefix;
    private String keyHash;

    /** JSON 数组 · 该 key 可访问的 KB id 列表 */
    private String allowedKbIds;

    private String scopeType;
    private Integer monthlyQuota;
    private Integer rateLimitQps;
    private Integer monthUsed;
    private String monthKey;
    private Long totalCalls;
    private LocalDateTime lastUsedAt;
    private LocalDateTime expireAt;

    /** active / revoked / expired */
    private String status;

    private Long createdBy;
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
