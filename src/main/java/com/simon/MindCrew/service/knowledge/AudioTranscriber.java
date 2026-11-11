package com.simon.MindCrew.service.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 音频识别 · DashScope Paraformer-v2 异步 API。
 *
 * 工作流：
 *   1. 提交任务（POST 文件 URL） → 返回 task_id
 *   2. 轮询任务状态 → SUCCEEDED / FAILED / RUNNING
 *   3. 任务完成后下载 transcription_url 拿到带时间戳的逐句 JSON
 *
 * 关键特性：
 *   - 句子级毫秒时间戳（begin_time / end_time）
 *   - 说话人分离（如果开启 diarization）
 *   - 中英文混合
 *   - 支持 mp3 / wav / m4a / aac / flac / opus / ogg / amr
 *
 * 限制：
 *   - 单文件 ≤ 2GB / ≤ 12 小时
 *   - 文件 URL 必须可被 DashScope 公网访问（用 MinIO/OSS 预签名 URL）
 *
 * 配置 application.yml:
 *   asr:
 *     model: paraformer-v2
 *     poll-interval-ms: 3000
 *     max-poll-times: 200          # 200 × 3s = 10 分钟超时
 *     enable-diarization: true     # 说话人分离
 */
@Slf4j
@Component
public class AudioTranscriber {

    private static final String SUBMIT_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";
    private static final String TASK_URL_PREFIX = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${asr.model:paraformer-v2}")
    private String model;

    @Value("${asr.poll-interval-ms:3000}")
    private long pollIntervalMs;

    @Value("${asr.max-poll-times:200}")
    private int maxPollTimes;

    @Value("${asr.enable-diarization:true}")
    private boolean enableDiarization;

    private final RestTemplate restTemplate = new RestTemplate();

    /** 单句转写结果 */
    public record Sentence(
            int index,           // 句子序号（从 1 起）
            String text,         // 文本
            long startMs,        // 起始毫秒
            long endMs,          // 结束毫秒
            String speakerId     // 说话人 ID（开了 diarization 才有，如 "spk_1"）
    ) {
        public long durationMs() { return endMs - startMs; }
        public String formatTime() {
            return String.format("%02d:%02d", startMs / 60000, (startMs / 1000) % 60);
        }
    }

    /** 转写完整结果 */
    public record TranscriptionResult(
            boolean success,
            String errorMsg,
            long totalDurationMs,
            List<Sentence> sentences
    ) {
        public static TranscriptionResult fail(String msg) {
            return new TranscriptionResult(false, msg, 0L, List.of());
        }
    }

    /**
     * 转写一个音频文件（通过其公网可访问的 URL）。
     */
    /** 是否配置了可用的 ASR Key（DashScope paraformer） */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"mock-key".equalsIgnoreCase(apiKey);
    }

    public TranscriptionResult transcribe(String audioUrl) {
        log.info("[ASR] 提交转写任务: {}", audioUrl);

        // ── 1. 提交任务 ───────────────────────────────
        String taskId;
        try {
            taskId = submitTask(audioUrl);
            log.info("[ASR] 任务已创建 task_id={}", taskId);
        } catch (Exception e) {
            log.error("[ASR] 提交任务失败", e);
            return TranscriptionResult.fail("ASR 任务提交失败: " + e.getMessage());
        }

        // ── 2. 轮询直到完成 ───────────────────────────
        String transcriptionUrl;
        try {
            transcriptionUrl = pollUntilDone(taskId);
            log.info("[ASR] 任务完成，结果地址: {}", transcriptionUrl);
        } catch (Exception e) {
            log.error("[ASR] 轮询任务失败", e);
            return TranscriptionResult.fail("ASR 轮询失败: " + e.getMessage());
        }

        // ── 3. 下载结果 ───────────────────────────────
        try {
            return downloadAndParse(transcriptionUrl);
        } catch (Exception e) {
            log.error("[ASR] 解析结果失败", e);
            return TranscriptionResult.fail("ASR 结果解析失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────

    private String submitTask(String audioUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.add("X-DashScope-Async", "enable");

        JSONObject input = new JSONObject();
        input.put("file_urls", JSONArray.of(audioUrl));

        JSONObject parameters = new JSONObject();
        parameters.put("language_hints", JSONArray.of("zh", "en"));
        if (enableDiarization) {
            parameters.put("diarization_enabled", true);
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        ResponseEntity<String> resp = restTemplate.exchange(
                SUBMIT_URL, HttpMethod.POST,
                new HttpEntity<>(body.toJSONString(), headers), String.class);

        JSONObject json = JSON.parseObject(resp.getBody());
        String taskId = json.getJSONObject("output").getString("task_id");
        if (taskId == null) {
            throw new RuntimeException("DashScope 返回无 task_id: " + resp.getBody());
        }
        return taskId;
    }

    private String pollUntilDone(String taskId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);

        for (int i = 0; i < maxPollTimes; i++) {
            Thread.sleep(pollIntervalMs);

            ResponseEntity<String> resp = restTemplate.exchange(
                    TASK_URL_PREFIX + taskId, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);

            JSONObject json = JSON.parseObject(resp.getBody());
            JSONObject output = json.getJSONObject("output");
            String status = output.getString("task_status");

            log.debug("[ASR] poll #{} task={} status={}", i + 1, taskId, status);

            if ("SUCCEEDED".equals(status)) {
                // results 是数组，可能多文件，我们只用第一个
                JSONArray results = output.getJSONArray("results");
                if (results == null || results.isEmpty()) {
                    throw new RuntimeException("任务完成但无结果: " + resp.getBody());
                }
                String transUrl = results.getJSONObject(0).getString("transcription_url");
                if (transUrl == null) {
                    throw new RuntimeException("结果中无 transcription_url");
                }
                return transUrl;
            }
            if ("FAILED".equals(status)) {
                String msg = output.getString("message");
                throw new RuntimeException("ASR 任务失败: " + msg);
            }
            // 继续轮询
        }
        throw new RuntimeException("ASR 任务超时（" + (pollIntervalMs * maxPollTimes / 1000) + "s）");
    }

    private TranscriptionResult downloadAndParse(String transcriptionUrl) {
        String body = restTemplate.getForObject(transcriptionUrl, String.class);
        JSONObject json = JSON.parseObject(body);

        // DashScope 结果结构: { transcripts: [ { text, sentences: [...] } ] }
        JSONArray transcripts = json.getJSONArray("transcripts");
        if (transcripts == null || transcripts.isEmpty()) {
            return TranscriptionResult.fail("结果文件无 transcripts");
        }

        List<Sentence> sentences = new ArrayList<>();
        long totalDuration = 0;
        int idx = 0;

        for (int i = 0; i < transcripts.size(); i++) {
            JSONObject transcript = transcripts.getJSONObject(i);
            JSONArray sentArr = transcript.getJSONArray("sentences");
            if (sentArr == null) continue;

            for (int j = 0; j < sentArr.size(); j++) {
                JSONObject s = sentArr.getJSONObject(j);
                String text = s.getString("text");
                if (text == null || text.isBlank()) continue;

                long begin = s.getLongValue("begin_time");
                long end = s.getLongValue("end_time");
                String speaker = s.containsKey("speaker_id")
                        ? "spk_" + s.getString("speaker_id") : null;

                sentences.add(new Sentence(++idx, text.trim(), begin, end, speaker));
                totalDuration = Math.max(totalDuration, end);
            }
        }

        log.info("[ASR] 解析完成: {} 句子, 总时长 {}ms", sentences.size(), totalDuration);
        return new TranscriptionResult(true, null, totalDuration, sentences);
    }

    /** 当前支持的音频扩展名 */
    public static List<String> supportedExtensions() {
        return List.of("mp3", "wav", "m4a", "aac", "flac", "opus", "ogg", "amr");
    }
}
