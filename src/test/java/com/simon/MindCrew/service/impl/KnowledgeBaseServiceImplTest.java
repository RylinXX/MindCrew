package com.simon.MindCrew.service.impl;

import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.MilvusService;
import com.simon.MindCrew.service.knowledge.TextChunker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KnowledgeBaseServiceImplTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void acceptsMarkdownUpload() throws Exception {
        MedKnowledgeBaseMapper mapper = mock(MedKnowledgeBaseMapper.class);
        MilvusService milvusService = mock(MilvusService.class);
        DocumentExtractor documentExtractor = mock(DocumentExtractor.class);
        TextChunker textChunker = mock(TextChunker.class);
        DocumentProcessTask processTask = mock(DocumentProcessTask.class);

        doAnswer(invocation -> {
            MedKnowledgeBase kb = invocation.getArgument(0);
            kb.setId(123L);
            return 1;
        }).when(mapper).insert(any(MedKnowledgeBase.class));

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                mapper, milvusService, documentExtractor, textChunker, processTask);
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString());

        TransactionSynchronizationManager.initSynchronization();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "team-handbook.md",
                "text/markdown",
                "# Team Handbook".getBytes());

        Long id = service.uploadDocument(file, "product", "internal handbook");

        ArgumentCaptor<MedKnowledgeBase> captor = ArgumentCaptor.forClass(MedKnowledgeBase.class);
        verify(mapper).insert(captor.capture());

        MedKnowledgeBase saved = captor.getValue();
        assertEquals(123L, id);
        assertEquals("md", saved.getFileType());
        assertEquals("uploading", saved.getStatus());
        assertTrue(saved.getFileUrl().endsWith(".md"));
        assertTrue(Files.exists(tempDir.resolve(saved.getFileUrl())));
    }
}
