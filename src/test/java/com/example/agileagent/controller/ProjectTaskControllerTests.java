package com.example.agileagent.controller;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.TaskPageResponse;
import com.example.agileagent.service.TaskIssueService;
import com.example.agileagent.service.TaskNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectTaskControllerTests {
    private final TaskIssueService service = mock(TaskIssueService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProjectTaskController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @BeforeEach
    void authenticate() { ProjectScope.authenticate(2L); }

    @AfterEach
    void clear() { ProjectScope.clear(); }

    @Test
    void listBindsFiltersAndTrustedProject() throws Exception {
        when(service.listTasks(eq(10L), eq(2), eq(5), eq("Alice"), eq("HIGH"), eq(false)))
                .thenAnswer(invocation -> {
                    org.junit.jupiter.api.Assertions.assertEquals(10L, ProjectScope.currentProjectId());
                    org.junit.jupiter.api.Assertions.assertEquals(2L, ProjectScope.requireUserId());
                    return new TaskPageResponse(2, 5, 0, List.of());
                });
        mvc.perform(get("/api/projects/10/tasks").param("page", "2").param("size", "5")
                        .param("assigneeName", "Alice").param("priority", "HIGH").param("completed", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2));
        org.junit.jupiter.api.Assertions.assertNull(ProjectScope.currentProjectId());
    }

    @Test
    void completionRequiresBooleanAndMissingTaskIsNotFound() throws Exception {
        mvc.perform(patch("/api/projects/10/tasks/101/completion")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        when(service.getTask(10L, 201L)).thenThrow(new TaskNotFoundException());
        mvc.perform(get("/api/projects/10/tasks/201"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
    }
}
