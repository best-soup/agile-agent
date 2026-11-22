package com.example.agileagent.service;

import com.example.agileagent.config.ProjectScope;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagMetadataTests {

    @AfterEach
    void clearScope() {
        ProjectScope.clear();
    }

    @Test
    void searchResultCarriesCitationMetadata() {
        Metadata metadata = Metadata.from("projectId", "10")
                .put("documentId", "doc-123")
                .put("sourceType", "MEETING")
                .put("sourceId", "api:/process")
                .put("title", "会议纪要")
                .put("meetingDate", "2026-10-16")
                .put("contentHash", "hash")
                .put("version", 2)
                .put("chunkIndex", 3);

        RagService.SearchResult result = RagService.SearchResult.from("慢查询优化", 0.91, metadata);

        assertTrue(result.toAgentFormat().contains("doc-123"));
        assertTrue(result.toAgentFormat().contains("第 3 段"));
        assertTrue(result.toAgentFormat().contains("慢查询优化"));
        assertTrue(result.citationLabel().contains("2026-10-16"));
    }

    @Test
    void projectFilterMatchesOnlyTheTrustedProject() {
        Filter filter = CompatibleQdrantEmbeddingStore.projectFilter(10L);

        assertTrue(filter.test(Metadata.from("projectId", "10")));
        assertFalse(filter.test(Metadata.from("projectId", "11")));
        assertFalse(filter.test(Metadata.from("title", "legacy")));
    }

    @Test
    void projectScopeRejectsARequestedProjectDifferentFromContext() {
        ProjectScope.authenticate(1L);
        ProjectScope.runInProject(10L, () -> {
            org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
                    () -> ProjectScope.requireProjectId(11L));
        });
    }
}
