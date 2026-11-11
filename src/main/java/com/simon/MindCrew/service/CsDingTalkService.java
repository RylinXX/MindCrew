package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.entity.CsDingTalkChannel;
import com.simon.MindCrew.entity.CsSession;
import com.simon.MindCrew.mapper.CsDingTalkChannelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服中心 · 钉钉机器人渠道
 *
 * 配置读写（secret AES 加密 / 回显打码）、回调验签、按 sender 建会话并同步回答。
 * 回调验签（契约 4.5）：sign = base64(HMAC_SHA256(sign_secret, timestamp + "\n" + app_secret))
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsDingTalkService {

    private static final String CHANNEL_DINGTALK = "dingtalk";
    private static final String MARKDOWN_TITLE = "MindCrew 智能客服";

    private final CsDingTalkChannelMapper channelMapper;
    private final CsSessionService sessionService;
    private final CsChatService chatService;
    private final CsConfigService configService;
    private final AesCryptoUtils aesCryptoUtils;

    // ─────────────────────────────────────────────
    // 配置
    // ─────────────────────────────────────────────

    /** 取首个渠道配置，缺失时落默认占位配置 */
    public CsDingTalkChannel getChannel() {
        CsDingTalkChannel channel = channelMapper.selectOne(new LambdaQueryWrapper<CsDingTalkChannel>()
                .orderByAsc(CsDingTalkChannel::getId)
                .last("LIMIT 1"));
        if (channel != null) return channel;

        channel = new CsDingTalkChannel();
        channel.setName("默认钉钉机器人");
        channel.setEnabled(0);
        channel.setWelcomeText("你好，我是 MindCrew 智能客服，有什么可以帮你？");
        channelMapper.insert(channel);
        log.info("[CS] 钉钉渠道配置缺失，已写入默认占位配置 id={}", channel.getId());
        return channel;
    }

    /** 管理端读取（secret 打码） */
    public CsDingTalkChannel getForAdmin() {
        return maskedCopy(getChannel());
    }

    /** 保存渠道配置（secret 为空或打码占位时保持不变） */
    @Transactional
    public CsDingTalkChannel save(CsDingTalkChannel dto) {
        if (dto == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "参数不能为空");
        }
        CsDingTalkChannel channel = getChannel();
        if (StringUtils.hasText(dto.getName())) channel.setName(dto.getName().trim());
        if (dto.getAppKey() != null) channel.setAppKey(dto.getAppKey());
        if (StringUtils.hasText(dto.getAppSecret()) && !dto.getAppSecret().contains("...")) {
            channel.setAppSecret(aesCryptoUtils.encrypt(dto.getAppSecret().trim()));
        }
        if (StringUtils.hasText(dto.getSignSecret()) && !dto.getSignSecret().contains("...")) {
            channel.setSignSecret(aesCryptoUtils.encrypt(dto.getSignSecret().trim()));
        }
        if (dto.getRobotCode() != null) channel.setRobotCode(dto.getRobotCode());
        if (dto.getWebhookUrl() != null) channel.setWebhookUrl(dto.getWebhookUrl());
        if (dto.getEnabled() != null) channel.setEnabled(dto.getEnabled());
        if (dto.getKbIds() != null) channel.setKbIds(dto.getKbIds());
        if (dto.getWelcomeText() != null) channel.setWelcomeText(dto.getWelcomeText());
        channelMapper.updateById(channel);
        return maskedCopy(channel);
    }

    // ─────────────────────────────────────────────
    // 管理端测试（不真发）
    // ─────────────────────────────────────────────

    /** 走 RAG 生成回复，仅返回文本 · 离线可验证 */
    public Map<String, Object> test(String content) {
        CsDingTalkChannel channel = getChannel();
        List<Long> kbIds = parseKbIds(channel.getKbIds());
        if (kbIds.isEmpty()) {
            kbIds = configService.kbIds(configService.getEntity());
        }
        return chatService.answerOnly(content, kbIds);
    }

    // ─────────────────────────────────────────────
    // 回调
    // ─────────────────────────────────────────────

    /**
     * 钉钉 outgoing 机器人回调：
     * 校验渠道 enabled + 签名 → 按 sender 复用/新建会话 → 同步 RAG 回答。
     */
    public Map<String, Object> handleCallback(Long channelId, String sign, String timestamp, String body) {
        CsDingTalkChannel channel = channelId == null ? getChannel() : channelMapper.selectById(channelId);
        if (channel == null || !Integer.valueOf(1).equals(channel.getEnabled())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "钉钉渠道不存在或已停用");
        }
        verifySign(channel, sign, timestamp);

        if (!StringUtils.hasText(body)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回调内容不能为空");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回调内容不是合法 JSON");
        }
        if (root == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回调内容不是合法 JSON");
        }

        JSONObject text = root.getJSONObject("text");
        String content = text == null ? null : text.getString("content");
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "消息内容不能为空");
        }

        String senderStaffId = root.getString("senderStaffId");
        String senderNick = root.getString("senderNick");
        if (!StringUtils.hasText(senderStaffId)) senderStaffId = "anonymous";

        String visitorKey = "ding:" + channel.getId() + ":" + senderStaffId;
        String kbIdsJson = channel.getKbIds();
        if (!StringUtils.hasText(kbIdsJson)) {
            kbIdsJson = configService.getEntity().getKbIds();
        }

        CsSession session = sessionService.findOrCreateForChannel(visitorKey, senderNick,
                CHANNEL_DINGTALK, "dingtalk:" + channel.getId(), kbIdsJson);
        sessionService.appendMessage(session.getId(), "user", content.trim(), null, null);
        String answer = chatService.answerForSession(session.getId(), content.trim());
        return markdownResponse(answer);
    }

    /** 回调验签：未配置 sign_secret 时跳过（便于本地联调） */
    private void verifySign(CsDingTalkChannel channel, String sign, String timestamp) {
        String signSecret = aesCryptoUtils.decrypt(channel.getSignSecret());
        if (!StringUtils.hasText(signSecret)) return;

        String appSecret = aesCryptoUtils.decrypt(channel.getAppSecret());
        if (!StringUtils.hasText(sign) || !StringUtils.hasText(timestamp) || !StringUtils.hasText(appSecret)) {
            throw new BusinessException(401, "签名校验失败");
        }
        String expected = CsTokenService.hmacBase64(signSecret, timestamp + "\n" + appSecret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(401, "签名校验失败");
        }
    }

    // ─────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────

    private Map<String, Object> markdownResponse(String answer) {
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", MARKDOWN_TITLE);
        markdown.put("text", answer);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msgtype", "markdown");
        resp.put("markdown", markdown);
        return resp;
    }

    private static List<Long> parseKbIds(String json) {
        if (!StringUtils.hasText(json)) return new ArrayList<>();
        try {
            List<Long> list = JSON.parseArray(json, Long.class);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private CsDingTalkChannel maskedCopy(CsDingTalkChannel src) {
        CsDingTalkChannel copy = new CsDingTalkChannel();
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setAppKey(src.getAppKey());
        copy.setAppSecret(AesCryptoUtils.mask(aesCryptoUtils.decrypt(src.getAppSecret())));
        copy.setSignSecret(AesCryptoUtils.mask(aesCryptoUtils.decrypt(src.getSignSecret())));
        copy.setRobotCode(src.getRobotCode());
        copy.setWebhookUrl(src.getWebhookUrl());
        copy.setEnabled(src.getEnabled());
        copy.setKbIds(src.getKbIds());
        copy.setWelcomeText(src.getWelcomeText());
        copy.setCreateTime(src.getCreateTime());
        copy.setUpdateTime(src.getUpdateTime());
        copy.setDeleted(src.getDeleted());
        return copy;
    }
}
