package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.VoicePersona;
import com.simon.MindCrew.service.CosyVoiceTtsService;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.UsageStatsService;
import com.simon.MindCrew.service.VoicePersonaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * TTS · 任务 14.2 + 音色管理
 *
 *   GET  /api/v2/tts/voices               · 列出可用音色
 *   POST /api/v2/tts/synth                · 一次性合成（短文本） · 返回 audio/wav
 *
 * 注：流式 TTS 在通话 WebSocket 内部使用，不单独暴露。
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/tts")
@RequiredArgsConstructor
public class TtsController {

    private final CosyVoiceTtsService ttsService;
    private final VoicePersonaService voicePersonaService;
    private final UserService userService;
    private final UsageStatsService usageStatsService;

    @GetMapping("/voices")
    public Result<List<VoicePersona>> voices() {
        Long uid = userService.getCurrentUserId();
        return Result.success(voicePersonaService.listAccessible(uid));
    }

    @PostMapping(value = "/synth", produces = "audio/wav")
    public ResponseEntity<byte[]> synth(@RequestBody SynthReq req) {
        if (req.getText() == null || req.getText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // 文本长度上限，防止滥用
        String text = req.getText().length() > 800 ? req.getText().substring(0, 800) : req.getText();

        VoicePersona voice = voicePersonaService.getOrDefault(req.getVoiceId());
        if (voice == null) {
            log.warn("[TTS] 没有可用音色（请跑 sql/voice-persona-schema.sql）");
            return ResponseEntity.status(503).build();
        }

        long t0 = System.currentTimeMillis();
        byte[] pcm = ttsService.synthesizeBlocking(text, voice);
        long elapsed = System.currentTimeMillis() - t0;
        int sampleRate = voice.getSampleRate() == null ? 22050 : voice.getSampleRate();
        log.info("[TTS] 合成完成 · voice={} text={}字 size={} bytes elapsed={}ms",
                voice.getVoiceId(), text.length(), pcm.length, elapsed);

        // 任务 13 · 用量记账（按字符数粗算）
        try {
            Long uid = userService.getCurrentUserId();
            if (uid != null) {
                int durationSec = pcm.length / (sampleRate * 2);  // PCM 16-bit mono
                usageStatsService.recordAsrAsync(uid, Math.max(1, durationSec));
            }
        } catch (Exception ignored) {}

        byte[] wav = pcmToWav(pcm, sampleRate, 1, 16);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("audio/wav"));
        h.setContentLength(wav.length);
        h.set("Cache-Control", "no-store");
        return new ResponseEntity<>(wav, h, 200);
    }

    /**
     * 把裸 PCM 包一层 WAV header，前端 Audio 元素能直接播放
     */
    private byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int chunkSize = 36 + dataSize;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(44 + dataSize);
            DataOutputStream out = new DataOutputStream(bos);
            // RIFF header
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(chunkSize));
            out.writeBytes("WAVE");
            // fmt sub-chunk
            out.writeBytes("fmt ");
            out.writeInt(Integer.reverseBytes(16));        // subchunk1 size (16 for PCM)
            out.writeShort(Short.reverseBytes((short) 1)); // audio format (1 = PCM)
            out.writeShort(Short.reverseBytes((short) channels));
            out.writeInt(Integer.reverseBytes(sampleRate));
            out.writeInt(Integer.reverseBytes(byteRate));
            out.writeShort(Short.reverseBytes((short) blockAlign));
            out.writeShort(Short.reverseBytes((short) bitsPerSample));
            // data sub-chunk
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(dataSize));
            out.write(pcm);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("WAV 封装失败", e);
        }
    }

    @Data
    public static class SynthReq {
        private String text;
        private Long voiceId;
    }
}
