package com.example.agileagent.service;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.entity.RagDocumentEntity;
import com.example.agileagent.mapper.RagDocumentMapper;
import com.example.agileagent.service.ProjectAccessService.Permission;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagDocumentDeduplicationTests {

    @AfterEach
    void clearScope() {
        ProjectScope.clear();
    }

    @Test
    void identicalSourceAndContentIsEmbeddedOnlyOnce() {
        OpenAiEmbeddingModel embeddingModel = mock(OpenAiEmbeddingModel.class);
        RagDocumentMapper documentMapper = mock(RagDocumentMapper.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        RagDocumentEntity ready = RagDocumentEntity.builder()
                .id(1L).documentId("doc-stable").projectId(10L)
                .sourceType("MEETING").sourceId("meeting-1")
                .contentHash("d29013d1195c9bd9a32e35ffe48e9f38a323e9d6d2af65f7fc357a083365cc8f")
                .version(1).status("READY").build();

        when(documentMapper.selectList(any())).thenReturn(List.of(), List.of(), List.of(ready));
        doAnswer(invocation -> {
            RagDocumentEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return 1;
        }).when(documentMapper).insert(any(RagDocumentEntity.class));
        when(documentMapper.updateById(any(RagDocumentEntity.class))).thenReturn(1);
        when(embeddingModel.embedAll(any())).thenReturn(Response.from(List.of(Embedding.from(new float[]{1.0f, 0.0f}))));

        RagService service = new RagService(embeddingModel, false, "localhost", 6334,
                "unused", documentMapper, access);

        ProjectScope.authenticate(7L);
        ProjectScope.runInProject(10L, () -> service.indexDocument(
                10L, "数据库慢查询需要优化。", "MEETING", "meeting-1", "会议纪要", "2026-10-16", null));
        ProjectScope.runInProject(10L, () -> service.indexDocument(
                10L, "数据库慢查询需要优化。", "MEETING", "meeting-1", "会议纪要", "2026-10-16", null));

        verify(embeddingModel, times(1)).embedAll(any());
        verify(documentMapper, times(1)).insert(any(RagDocumentEntity.class));
        verify(access, times(2)).requireCurrentProject(10L, Permission.WRITE);
    }

    @Test
    void embeddingFailureDoesNotBecomeReady() {
        OpenAiEmbeddingModel embeddingModel = mock(OpenAiEmbeddingModel.class);
        RagDocumentMapper documentMapper = mock(RagDocumentMapper.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(), List.of());
        doAnswer(invocation -> {
            RagDocumentEntity entity = invocation.getArgument(0);
            entity.setId(2L);
            return 1;
        }).when(documentMapper).insert(any(RagDocumentEntity.class));
        when(embeddingModel.embedAll(any())).thenThrow(new IllegalStateException("embedding unavailable"));

        RagService service = new RagService(embeddingModel, false, "localhost", 6334,
                "unused", documentMapper, access);
        ProjectScope.authenticate(7L);

        assertThrows(IllegalStateException.class, () -> ProjectScope.runInProject(10L,
                () -> service.indexDocument(10L, "数据库慢查询需要优化。", "MEETING", "meeting-2",
                        "会议纪要", "2026-10-16", null)));
        verify(documentMapper).updateById(any(RagDocumentEntity.class));
    }
}
