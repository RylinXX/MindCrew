package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.VoicePersona;
import com.simon.MindCrew.mapper.VoicePersonaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 音色管理 · 任务 14
 *
 * 当前阶段：仅查询预置音色 + 默认音色解析。
 * 自定义音色复刻（14.1）后续接入：写入时 owner_user_id 必填。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoicePersonaService {

    private final VoicePersonaMapper mapper;

    /** 所有可用音色（用户可见：预置 + 自己拥有的） */
    public List<VoicePersona> listAccessible(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<VoicePersona>()
                .eq(VoicePersona::getEnabled, 1)
                .and(w -> w.isNull(VoicePersona::getOwnerUserId)
                          .or().eq(userId != null, VoicePersona::getOwnerUserId, userId))
                .orderByAsc(VoicePersona::getSortOrder));
    }

    /** 用 ID 取音色，找不到回退到默认 */
    public VoicePersona getOrDefault(Long voiceId) {
        if (voiceId != null) {
            VoicePersona p = mapper.selectById(voiceId);
            if (p != null && Integer.valueOf(1).equals(p.getEnabled())) return p;
        }
        return getDefault();
    }

    public VoicePersona getDefault() {
        VoicePersona p = mapper.selectOne(new LambdaQueryWrapper<VoicePersona>()
                .eq(VoicePersona::getIsDefault, 1)
                .eq(VoicePersona::getEnabled, 1)
                .last("LIMIT 1"));
        if (p != null) return p;
        // 兜底：返回任意一个 enabled 音色
        return mapper.selectOne(new LambdaQueryWrapper<VoicePersona>()
                .eq(VoicePersona::getEnabled, 1)
                .orderByAsc(VoicePersona::getSortOrder)
                .last("LIMIT 1"));
    }

    public VoicePersona getById(Long id) {
        return mapper.selectById(id);
    }
}
