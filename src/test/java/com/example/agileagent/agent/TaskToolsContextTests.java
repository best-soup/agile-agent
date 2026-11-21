package com.example.agileagent.agent;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.entity.TaskIssueEntity;
import com.example.agileagent.service.TaskIssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskToolsContextTests {
    @AfterEach
    void clear() { ProjectScope.clear(); }

    @Test
    void queryTasksRestoresUserProjectAndRequestContextFromLangChainMemoryId() {
        TaskIssueService service = mock(TaskIssueService.class);
        when(service.listAllTasks(10L, null)).thenAnswer(invocation -> {
            assertTrue(ProjectScope.currentUserId() == 2L);
            assertTrue(ProjectScope.currentProjectId() == 10L);
            assertTrue("stream-request".equals(ProjectScope.currentRequestId()));
            return List.of(TaskIssueEntity.builder().id(1L).projectId(10L).title("真实任务").priority("HIGH").build());
        });
        TaskTools tools = new TaskTools();
        ReflectionTestUtils.setField(tools, "taskIssueService", service);

        String result = tools.queryTasks(10L, "ctx|2|10|stream-request");

        assertTrue(result.contains("真实任务"));
        assertTrue(ProjectScope.currentUserId() == null);
        verify(service).listAllTasks(10L, null);
    }
}
