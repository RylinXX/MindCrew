package com.simon.MindCrew.service.knowledge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentExtractorTest {

    // markdown 纯文本抽取路径不依赖 OfficeConverter / VisionRecognizer / AudioTranscriber
    private final DocumentExtractor extractor = new DocumentExtractor(null, null, null);

    @Test
    void extractsMarkdownAsPlainText() {
        String markdown = "# MindCrew\n\n- agentic rag\n- mcp\n";

        String extracted = extractor.extract(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)),
                "md");

        assertEquals(markdown, extracted);
    }

    @Test
    void supportsMarkdownExtensionAlias() {
        String markdown = "## Release Notes\n\n- add markdown upload\n";

        String extracted = extractor.extract(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)),
                "markdown");

        assertEquals(markdown, extracted);
    }
}
