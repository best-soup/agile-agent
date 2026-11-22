package com.example.agileagent.controller;

import com.example.agileagent.agent.AgileMasterAgent;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.service.ProjectAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskControllerChatContextTests {

    @AfterEach
    void clearScope() {
        ProjectScope.clear();
    }

    @Test
    void normalChatPassesTrustedProjectIdAndScopedMemoryIdToAgent() {
        AgileMasterAgent agent = mock(AgileMasterAgent.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        TaskController controller = new TaskController();
        ReflectionTestUtils.setField(controller, "agileMasterAgent", agent);
        ReflectionTestUtils.setField(controller, "projectAccessService", access);

        when(agent.chat(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            String memoryId = invocation.getArgument(0);
            Long projectId = invocation.getArgument(1);
            assertEquals("ctx|7|2|chat-request-2", memoryId);
            assertEquals(2L, projectId);
            assertEquals(7L, ProjectScope.currentUserId());
            assertEquals(2L, ProjectScope.currentProjectId());
            assertEquals("chat-request-2", ProjectScope.currentRequestId());
            return "BETA_PROJECT_ONLY";
        });

        ProjectScope.authenticate(7L, "chat-request-2");
        String response = controller.chatWithAgent(2L, "查询当前项目的历史会议");

        assertTrue(response.contains("BETA_PROJECT_ONLY"));
        verify(access).requireCurrentProject(2L, ProjectAccessService.Permission.READ);
        assertEquals(null, ProjectScope.currentProjectId());
    }
}
