package com.simon.MindCrew.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(DocmindWebSearchProperties.class)
public class WebSearchConfig {

    @Bean
    public RestTemplate webSearchRestTemplate(RestTemplateBuilder builder,
                                              DocmindWebSearchProperties properties) {
        return builder
                .setConnectTimeout(properties.getTimeout())
                .setReadTimeout(properties.getTimeout())
                .build();
    }
}
