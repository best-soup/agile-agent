package com.example.agileagent.agent;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.service.ProjectAccessService;
import com.example.agileagent.service.RagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeToolsContextTests {

    @AfterEach
    void clearScope() {
        ProjectScope.clear();
    }

    @Test
    void toolCannotOverrideTrustedProjectContext() {
        RagService ragService = mock(RagService.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        KnowledgeTools tools = new KnowledgeTools();
        ReflectionTestUtils.setField(tools, "ragService", ragService);
        ReflectionTestUtils.setField(tools, "projectAccessService", access);

        ProjectScope.authenticate(7L, "request-2");
        String memoryId = ProjectScope.runInProject(2L, () -> ProjectScope.encodeMemoryId(2L));

        assertThrows(SecurityException.class,
                () -> tools.searchKnowledge(1L, "BETA_PROJECT_ONLY", memoryId));
        verify(access, never()).requireCurrentProject(eq(1L), eq(ProjectAccessService.Permission.READ));
        verify(ragService, never()).search(anyLong(), anyString(), anyInt());
    }

    @Test
    void missingModelProjectIdFallsBackToTrustedRequestProject() {
        RagService ragService = mock(RagService.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        KnowledgeTools tools = new KnowledgeTools();
        ReflectionTestUtils.setField(tools, "ragService", ragService);
        ReflectionTestUtils.setField(tools, "projectAccessService", access);
        when(ragService.search(2L, "BETA_PROJECT_ONLY", 3)).thenReturn(java.util.List.of());

        ProjectScope.authenticate(7L, "request-2");
        String memoryId = ProjectScope.runInProject(2L, () -> ProjectScope.encodeMemoryId(2L));

        tools.searchKnowledge(null, "BETA_PROJECT_ONLY", memoryId);

        verify(ragService).search(2L, "BETA_PROJECT_ONLY", 3);
        verify(access).requireCurrentProject(2L, ProjectAccessService.Permission.READ);
    }
}
