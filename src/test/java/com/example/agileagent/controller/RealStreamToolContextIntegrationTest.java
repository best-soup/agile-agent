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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    @DynamicPropertySource
    static void realEnvironment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> envOrDefault("SPRING_DATASOURCE_URL",
                "jdbc:h2:mem:agile_agent_real_stream;MODE=MySQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> envOrDefault("SPRING_DATASOURCE_DRIVER_CLASS_NAME",
                "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> envOrDefault("SPRING_DATASOURCE_USERNAME", "sa"));
        registry.add("spring.datasource.password", () -> envOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        registry.add("spring.flyway.enabled", () -> envOrDefault("SPRING_FLYWAY_ENABLED", "false"));
        registry.add("qdrant.enabled", () -> "false");
        String dashScopeUrl = envOrDefault("DASHSCOPE_BASE_URL",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String dashScopeKey = envOrDefault("DASHSCOPE_API_KEY", "");
        registry.add("langchain4j.open-ai.chat-model.base-url", () -> dashScopeUrl);
        registry.add("langchain4j.open-ai.chat-model.api-key", () -> dashScopeKey);
        registry.add("langchain4j.open-ai.chat-model.model-name", () -> "qwen4-max");
        registry.add("langchain4j.open-ai.streaming-chat-model.base-url", () -> dashScopeUrl);
        registry.add("langchain4j.open-ai.streaming-chat-model.api-key", () -> dashScopeKey);
        registry.add("langchain4j.open-ai.streaming-chat-model.model-name", () -> "qwen4-max");
        registry.add("langchain4j.open-ai.embedding-model.base-url", () -> dashScopeUrl);
        registry.add("langchain4j.open-ai.embedding-model.api-key", () -> dashScopeKey);
        registry.add("langchain4j.open-ai.embedding-model.model-name", () -> "text-embedding-v3");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

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
        String prompt = "必须立即调用 queryTasks 工具，参数 projectId=" + projectId
                + "。不要猜测、不要直接回答，工具返回后逐条列出真实工单列表。";
        ResponseEntity<String> response = new RestTemplate().postForEntity(
                "http://localhost:" + port + "/api/task/chat/stream?projectId=" + projectId,
                new HttpEntity<>(prompt, headers), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        String normalizedSse = response.getBody().replace("data:", "").replaceAll("\\R", "");
        assertTrue(normalizedSse.contains(taskMarker),
                "SSE response did not contain the expected real task marker; body=" + response.getBody());
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
