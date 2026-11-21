package com.example.agileagent.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in real integration check. It requires MySQL, Redis, DashScope and a valid API key.
 * Run with AGILE_REAL_STREAM_TEST=true and the environment variables described below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("real-stream-test")
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "AGILE_REAL_STREAM_TEST", matches = "true")
class RealStreamToolContextIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    void streamQueryTasksRestoresToolContextAndRequestId(CapturedOutput output) {
        String apiKey = required("AGILE_TEST_API_KEY");
        String projectId = required("AGILE_TEST_PROJECT_ID");
        String requestId = required("AGILE_TEST_REQUEST_ID");
        String taskMarker = required("AGILE_TEST_TASK_MARKER");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.set("X-Request-Id", requestId);
        String prompt = "请强制调用 queryTasks 工具查询当前项目的全部工单，不要直接编造答案。返回工具查到的真实工单列表。";
        ResponseEntity<String> response = new RestTemplate().postForEntity(
                "http://localhost:" + port + "/api/task/chat/stream?projectId=" + projectId,
                new HttpEntity<>(prompt, headers), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains(taskMarker), "SSE response did not contain the expected real task marker");
        String logs = output.getOut() + output.getErr();
        assertFalse(logs.contains("缺少当前用户上下文"), "Tool execution lost ProjectScope context");
        assertTrue(logs.contains("task_issue"), "MyBatis SQL log did not show task_issue query");
        assertTrue(logs.contains("requestId=" + requestId), "requestId was not propagated to async Tool logs");
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
