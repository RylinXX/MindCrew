package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.CsDingTalkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 客服中心 · 钉钉回调（白名单，自行验签）
 *
 * 路径：/api/cs/dingtalk/**
 * 契约：docs/design-cs-center.md 4.5
 *   POST /callback?channelId=1&sign=&timestamp=
 *   body = 钉钉 outgoing 机器人 JSON（senderStaffId / senderNick / text.content）
 */
@Slf4j
@RestController
@RequestMapping("/api/cs/dingtalk")
@RequiredArgsConstructor
public class CsDingTalkController {

    private final CsDingTalkService dingTalkService;

    @PostMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) Long channelId,
                                      @RequestParam(required = false) String sign,
                                      @RequestParam(required = false) String timestamp,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String rawBody = body == null ? null : JSON.toJSONString(body);
        try {
            Map<String, Object> resp = dingTalkService.handleCallback(channelId, sign, timestamp, rawBody);
            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Integer code = e.getCode();
            if (code != null && code == 401) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Result.error(401, e.getMessage()));
            }
            if (code != null && code == 403) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Result.error(403, e.getMessage()));
            }
            throw e;
        } catch (Exception e) {
            log.error("[CS] 钉钉回调处理失败", e);
            Map<String, Object> md = new java.util.LinkedHashMap<>();
            md.put("title", "MindCrew 智能客服");
            md.put("text", "抱歉，服务暂时不可用，请稍后再试。");
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("msgtype", "markdown");
            resp.put("markdown", md);
            return ResponseEntity.ok(resp);
        }
    }
}
