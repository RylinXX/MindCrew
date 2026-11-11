package com.simon.MindCrew.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 kb_chunk 的全文索引存在。
 * 若数据库不支持 ngram parser，则记录告警并自动降级为应用内 BM25。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbChunkIndexInitializer implements ApplicationRunner {

    private static final String INDEX_NAME = "ft_kb_chunk_content_ngram";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer indexCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(1)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'kb_chunk'
                      AND index_name = ?
                    """, Integer.class, INDEX_NAME);

            if (indexCount != null && indexCount > 0) {
                log.info("kb_chunk 全文索引已存在: {}", INDEX_NAME);
                return;
            }

            jdbcTemplate.execute("""
                    ALTER TABLE kb_chunk
                    ADD FULLTEXT INDEX ft_kb_chunk_content_ngram (content) WITH PARSER ngram
                    """);
            log.info("kb_chunk 全文索引创建成功: {}", INDEX_NAME);
        } catch (Exception e) {
            log.warn("kb_chunk 全文索引创建失败，将降级到应用内中文 BM25: {}", e.getMessage());
        }
    }
}
