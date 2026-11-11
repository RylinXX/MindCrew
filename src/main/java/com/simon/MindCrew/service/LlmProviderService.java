package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.entity.LlmProvider;
import com.simon.MindCrew.mapper.LlmProviderMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider 管理服务。
 *
 * 启动时加载激活 provider 到内存，并提供：
 *   - getActive()                  · 拿激活 provider（含解密后的 api_key）
 *   - getDecryptedApiKey(p)        · 单独解密某个 provider 的 key
 *   - setActive(id)                · 切换激活（自动 refresh AI 配置）
 *   - testConnectivity(provider)   · 调用 chat/completions 测试连通
 *   - createWithEncryption(...)    · 创建时自动加密 key
 *   - updateWithEncryption(...)    · 更新时自动加密 key
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderService {

    private final LlmProviderMapper providerMapper;
    private final AesCryptoUtils aesCrypto;

    /** 当前激活 provider 的快速引用 */
    private final AtomicReference<LlmProvider> activeRef = new AtomicReference<>();

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        refresh();
    }

    public synchronized void refresh() {
        try {
            LlmProvider active = providerMapper.selectOne(
                    new LambdaQueryWrapper<LlmProvider>()
                            .eq(LlmProvider::getIsActive, 1)
                            .eq(LlmProvider::getEnabled, 1)
                            .last("LIMIT 1")
            );
            activeRef.set(active);
            if (active != null) {
                log.info("[LlmProvider] 激活 Provider: name={} chatModel={} baseUrl={}",
                        active.getName(), active.getChatModel(), active.getBaseUrl());
            } else {
                log.warn("[LlmProvider] 未配置激活 Provider，将回退到 application.yml 默认配置");
            }
        } catch (Exception e) {
            // 表不存在 / DB 连不上等场景：不影响 Spring 启动，回退到 yml
            activeRef.set(null);
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("doesn't exist") || msg.contains("llm_provider")) {
                log.warn("[LlmProvider] 表 llm_provider 不存在，跳过 DB 加载（请执行 sql/llm-provider-schema.sql）。" +
                        "本次启动将使用 application.yml 中的 llm.* 默认配置。");
            } else {
                log.warn("[LlmProvider] DB 读取失败，回退到 yml 默认配置: {}", msg);
            }
        }
    }

    // ─────────────────────────────────────────────
    // 读取
    // ─────────────────────────────────────────────
    public LlmProvider getActive() {
        return activeRef.get();
    }

    public LlmProvider getById(Long id) {
        return id == null ? null : providerMapper.selectById(id);
    }

    public List<LlmProvider> listAll() {
        return providerMapper.selectList(
                new LambdaQueryWrapper<LlmProvider>()
                        .orderByAsc(LlmProvider::getSortOrder)
        );
    }

    /** 取激活 provider 的解密 API Key。无激活时返回 null。 */
    public String getActiveApiKey() {
        LlmProvider p = getActive();
        return p == null ? null : aesCrypto.decrypt(p.getApiKeyEnc());
    }

    public String decryptKey(LlmProvider p) {
        return p == null ? null : aesCrypto.decrypt(p.getApiKeyEnc());
    }

    // ─────────────────────────────────────────────
    // 写入
    // ─────────────────────────────────────────────
    public Long create(LlmProvider p, String plainApiKey) {
        p.setApiKeyEnc(plainApiKey == null ? null : aesCrypto.encrypt(plainApiKey));
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getIsActive() == null) p.setIsActive(0);
        if (p.getSortOrder() == null) p.setSortOrder(100);
        providerMapper.insert(p);
        refresh();
        return p.getId();
    }

    /**
     * 更新 provider。如果 plainApiKey 传 null，保留原值；传空串"清空 key"；传非空串则加密替换。
     */
    public void update(LlmProvider p, String plainApiKey) {
        if (plainApiKey == null) {
            // 保留原 key
            LlmProvider db = providerMapper.selectById(p.getId());
            if (db != null) p.setApiKeyEnc(db.getApiKeyEnc());
        } else if (plainApiKey.isEmpty()) {
            p.setApiKeyEnc(null);
        } else {
            p.setApiKeyEnc(aesCrypto.encrypt(plainApiKey));
        }
        providerMapper.updateById(p);
        refresh();
    }

    public void delete(Long id) {
        LlmProvider db = providerMapper.selectById(id);
        if (db != null && Integer.valueOf(1).equals(db.getIsActive())) {
            throw new IllegalStateException("不能删除当前激活的 Provider，请先切换到其他 Provider");
        }
        providerMapper.deleteById(id);
        refresh();
    }

    /** 设为激活（同时取消其他激活）。返回该 provider 给上层用于立即重建 ChatModel。 */
    public synchronized LlmProvider setActive(Long id) {
        // 取消所有激活
        LlmProvider reset = new LlmProvider();
        reset.setIsActive(0);
        providerMapper.update(reset, new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getIsActive, 1));

        // 设新激活
        LlmProvider target = providerMapper.selectById(id);
        if (target == null) throw new IllegalArgumentException("Provider 不存在");
        target.setIsActive(1);
        providerMapper.updateById(target);
        refresh();
        return target;
    }

    // ─────────────────────────────────────────────
    // 连通性测试
    // ─────────────────────────────────────────────
    /**
     * 调用 chat/completions 测试连通。
     * @param providerId 要测试的 provider
     * @param overridePlainKey 可选：用临时未保存的明文 key 测试（管理后台填了新 key 但没保存场景）
     */
    public TestResult testConnectivity(Long providerId, String overridePlainKey) {
        LlmProvider p = providerMapper.selectById(providerId);
        if (p == null) return new TestResult(false, "Provider 不存在");
        return testConnectivity(p, overridePlainKey);
    }

    public TestResult testConnectivity(LlmProvider p, String overridePlainKey) {
        long t0 = System.currentTimeMillis();
        try {
            String apiKey = (overridePlainKey != null && !overridePlainKey.isBlank())
                    ? overridePlainKey
                    : aesCrypto.decrypt(p.getApiKeyEnc());

            String url = p.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";

            JSONObject body = new JSONObject();
            body.put("model", p.getChatModel());
            body.put("max_tokens", 8);
            body.put("messages", JSON.parseArray(
                    "[{\"role\":\"user\",\"content\":\"ping\"}]"
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank() && !"EMPTY".equals(apiKey)) {
                headers.setBearerAuth(apiKey);
            }

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body.toJSONString(), headers), String.class);

            long elapsed = System.currentTimeMillis() - t0;
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            String msg = ok ? "连通正常 · " + elapsed + "ms" : "HTTP " + resp.getStatusCode();
            updateTestResult(p.getId(), ok, msg);
            return new TestResult(ok, msg);
        } catch (Exception e) {
            String err = e.getMessage();
            if (err != null && err.length() > 200) err = err.substring(0, 200);
            updateTestResult(p.getId(), false, err);
            return new TestResult(false, err);
        }
    }

    private void updateTestResult(Long id, boolean ok, String msg) {
        try {
            LlmProvider patch = new LlmProvider();
            patch.setId(id);
            patch.setLastTestAt(LocalDateTime.now());
            patch.setLastTestOk(ok ? 1 : 0);
            patch.setLastTestMsg(msg);
            providerMapper.updateById(patch);
        } catch (Exception ignored) {}
    }

    public record TestResult(boolean success, String message) {}
}
