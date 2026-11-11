package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.LlmProvider;
import com.simon.MindCrew.service.LlmProviderService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨厂商模型 Provider 管理 API
 *
 * 路由：/api/v2/llm-provider
 *   GET    /list                    列出所有 Provider（API Key 脱敏）
 *   GET    /active                  当前激活 Provider
 *   POST   /                        创建（admin only）
 *   PUT    /{id}                    更新（admin only）
 *   POST   /{id}/set-active         设为激活（admin only），自动重建 ChatModel
 *   POST   /{id}/test               连通性测试（admin only）
 *   POST   /test                    用未保存的配置临时测试
 *   DELETE /{id}                    删除（admin only）
 */
@RestController
@RequestMapping("/api/v2/llm-provider")
@RequiredArgsConstructor
public class LlmProviderController {

    private final LlmProviderService providerService;
    private final AiConfigHolder aiConfigHolder;

    // ─────────────────────────────────────────────
    // 读取
    // ─────────────────────────────────────────────
    @GetMapping("/list")
    public Result<List<ProviderVO>> list() {
        List<LlmProvider> all = providerService.listAll();
        List<ProviderVO> out = new ArrayList<>();
        for (LlmProvider p : all) out.add(ProviderVO.from(p, providerService));
        return Result.success(out);
    }

    @GetMapping("/active")
    public Result<ProviderVO> active() {
        LlmProvider p = providerService.getActive();
        return Result.success(p == null ? null : ProviderVO.from(p, providerService));
    }

    @GetMapping("/{id}")
    public Result<ProviderVO> getById(@PathVariable Long id) {
        LlmProvider p = providerService.getById(id);
        return p == null ? Result.error("Provider 不存在") : Result.success(ProviderVO.from(p, providerService));
    }

    // ─────────────────────────────────────────────
    // 写入（管理员）
    // ─────────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody ProviderDTO dto) {
        LlmProvider p = dto.toEntity();
        Long id = providerService.create(p, dto.getApiKey());
        return Result.success(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody ProviderDTO dto) {
        LlmProvider p = dto.toEntity();
        p.setId(id);
        // apiKey 字段语义：null=保留原值，空串=清空，非空=替换
        providerService.update(p, dto.getApiKey());
        // 如果改的是激活的 provider 也立刻重建 ChatModel
        LlmProvider active = providerService.getActive();
        if (active != null && active.getId().equals(id)) {
            aiConfigHolder.refreshLlmModel();
        }
        return Result.success();
    }

    @PostMapping("/{id}/set-active")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setActive(@PathVariable Long id) {
        providerService.setActive(id);
        // 切换后立即重建 ChatModel Bean
        aiConfigHolder.refreshLlmModel();
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return Result.success();
    }

    // ─────────────────────────────────────────────
    // 测试连通性
    // ─────────────────────────────────────────────
    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> test(@PathVariable Long id, @RequestBody(required = false) TestDTO dto) {
        String overrideKey = dto == null ? null : dto.getApiKey();
        LlmProviderService.TestResult r = providerService.testConnectivity(id, overrideKey);
        return Result.success(Map.of("success", r.success(), "message", r.message()));
    }

    /** 用未保存的配置临时测试（管理员填新 endpoint+key 但没保存场景）*/
    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> testRaw(@RequestBody ProviderDTO dto) {
        LlmProvider tmp = dto.toEntity();
        // 这是临时未持久化的 provider，跳过加密直接用明文测试
        LlmProviderService.TestResult r = providerService.testConnectivity(tmp, dto.getApiKey());
        return Result.success(Map.of("success", r.success(), "message", r.message()));
    }

    // ─────────────────────────────────────────────
    // DTO / VO
    // ─────────────────────────────────────────────
    @Data
    public static class ProviderDTO {
        private String name;
        private String providerType;
        private String baseUrl;
        private String apiKey;             // 明文（创建/更新时传入；null 表示保留原值）
        private String chatModel;
        private String embeddingModel;
        private Integer embeddingDim;
        private BigDecimal temperature;
        private String description;
        private Integer enabled;
        private Integer sortOrder;

        public LlmProvider toEntity() {
            LlmProvider p = new LlmProvider();
            p.setName(name);
            p.setProviderType(providerType == null ? "openai_compatible" : providerType);
            p.setBaseUrl(baseUrl);
            p.setChatModel(chatModel);
            p.setEmbeddingModel(embeddingModel);
            p.setEmbeddingDim(embeddingDim);
            p.setTemperature(temperature == null ? BigDecimal.valueOf(0.7) : temperature);
            p.setDescription(description);
            p.setEnabled(enabled == null ? 1 : enabled);
            p.setSortOrder(sortOrder == null ? 100 : sortOrder);
            return p;
        }
    }

    @Data
    public static class TestDTO {
        private String apiKey;
    }

    @Data
    public static class ProviderVO {
        private Long id;
        private String name;
        private String providerType;
        private String baseUrl;
        private String apiKeyMasked;       // 脱敏 key（前 4 ... 后 4）
        private boolean apiKeySet;         // 是否已设置 key
        private String chatModel;
        private String embeddingModel;
        private Integer embeddingDim;
        private BigDecimal temperature;
        private String description;
        private Integer isActive;
        private Integer enabled;
        private Integer sortOrder;
        private String lastTestAt;
        private Integer lastTestOk;
        private String lastTestMsg;

        public static ProviderVO from(LlmProvider p, LlmProviderService svc) {
            ProviderVO v = new ProviderVO();
            v.setId(p.getId());
            v.setName(p.getName());
            v.setProviderType(p.getProviderType());
            v.setBaseUrl(p.getBaseUrl());
            String decKey = svc.decryptKey(p);
            v.setApiKeySet(decKey != null && !decKey.isBlank());
            v.setApiKeyMasked(AesCryptoUtils.mask(decKey));
            v.setChatModel(p.getChatModel());
            v.setEmbeddingModel(p.getEmbeddingModel());
            v.setEmbeddingDim(p.getEmbeddingDim());
            v.setTemperature(p.getTemperature());
            v.setDescription(p.getDescription());
            v.setIsActive(p.getIsActive());
            v.setEnabled(p.getEnabled());
            v.setSortOrder(p.getSortOrder());
            v.setLastTestAt(p.getLastTestAt() == null ? null : p.getLastTestAt().toString());
            v.setLastTestOk(p.getLastTestOk());
            v.setLastTestMsg(p.getLastTestMsg());
            return v;
        }
    }
}
