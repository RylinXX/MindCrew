package com.simon.MindCrew.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端配置 · 仅在 storage.type=oss 时启用
 *
 * 必填配置:
 *   oss.endpoint     · 如 https://oss-cn-hangzhou.aliyuncs.com
 *   oss.region       · 如 cn-hangzhou（V4 签名必填）
 *   oss.access-key
 *   oss.secret-key
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "storage.type", havingValue = "oss")
public class OssConfig {

    @Value("${oss.endpoint}")
    private String endpoint;

    @Value("${oss.region:}")
    private String region;

    @Value("${oss.access-key}")
    private String accessKey;

    @Value("${oss.secret-key}")
    private String secretKey;

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient() {
        log.info("[OSS] 初始化客户端 endpoint={} region={}", endpoint, region);
        return new OSSClientBuilder().build(endpoint, accessKey, secretKey);
    }
}
