package com.example.agileagent.controller;

import com.example.agileagent.agent.AgileMasterAgent;
import com.example.agileagent.agent.ManualReActAgent;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.service.ProjectAccessService;
import com.example.agileagent.service.RagService;
import com.example.agileagent.service.TaskDraftService;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TaskControllerConcurrencyTests {
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void clear() {
        ProjectScope.clear();
        if (executor != null) executor.shutdown();
    }

    @Test
    void streamStartsOnControlledExecutorWithExplicitProjectContext() throws Exception {
        AgileMasterAgent agent = mock(AgileMasterAgent.class);
        ManualReActAgent manual = mock(ManualReActAgent.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        TokenStream stream = mock(TokenStream.class);
        CountDownLatch started = new CountDownLatch(1);
        when(agent.chatStream(anyString(), anyLong(), anyString())).thenReturn(stream);
        when(stream.onPartialResponse(any())).thenReturn(stream);
        when(stream.onCompleteResponse(any())).thenReturn(stream);
        when(stream.onError(any())).thenReturn(stream);
        doAnswer(invocation -> {
            assertTrue(ProjectScope.currentUserId() == 2L);
            assertTrue(ProjectScope.currentProjectId() == 10L);
            assertTrue("request-10".equals(ProjectScope.currentRequestId()));
            started.countDown();
            return null;
        }).when(stream).start();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("test-sse-");
        executor.initialize();

        TaskController controller = new TaskController();
        ReflectionTestUtils.setField(controller, "agileMasterAgent", agent);
        ReflectionTestUtils.setField(controller, "manualReActAgent", manual);
        ReflectionTestUtils.setField(controller, "projectAccessService", access);
        ReflectionTestUtils.setField(controller, "ragService", mock(RagService.class));
        ReflectionTestUtils.setField(controller, "taskDraftService", mock(TaskDraftService.class));
        ReflectionTestUtils.setField(controller, "sseTaskExecutor", executor);
        ReflectionTestUtils.setField(controller, "sseTimeoutMs", 1000L);

        ProjectScope.authenticate(2L, "request-10");
        SseEmitter emitter = controller.chatStream(10L, "hello");
        assertTrue(started.await(2, TimeUnit.SECONDS));
        verify(stream, timeout(1000)).start();
        assertTrue(emitter != null);
        assertTrue(ProjectScope.currentProjectId() == null);
    }
}
