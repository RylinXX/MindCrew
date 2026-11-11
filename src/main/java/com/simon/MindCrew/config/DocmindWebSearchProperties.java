package com.simon.MindCrew.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "mindcrew.web-search")
public class DocmindWebSearchProperties {

    /**
     * 是否启用公网搜索。
     */
    private boolean enabled = false;

    /**
     * Tavily 搜索 API 端点。
     */
    private String tavilyEndpoint = "https://api.tavily.com/search";

    /**
     * Tavily Bearer API Key。
     */
    private String apiKey;

    /**
     * HTTP 超时。
     */
    private Duration timeout = Duration.ofSeconds(10);
}
