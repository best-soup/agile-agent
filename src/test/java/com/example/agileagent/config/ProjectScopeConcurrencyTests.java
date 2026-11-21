package com.example.agileagent.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ProjectScopeConcurrencyTests {
    @Test
    void contextIsExplicitlyCapturedAndClearedWhenExecutorThreadIsReused() throws Exception {
        ProjectScope.runAs(7L, 70L, () -> {
            ProjectScope.authenticate(7L, "request-7");
            return null;
        });
        ProjectScope.runAs(7L, 70L, () -> {
            ProjectScope.Context context = new ProjectScope.Context(7L, 70L, "request-7");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<ProjectScope.Context> first = executor.submit(() -> ProjectScope.runWith(context, ProjectScope::capture));
                Future<ProjectScope.Context> second = executor.submit(ProjectScope::capture);
                try {
                    assertEquals(context, first.get());
                    assertNull(second.get());
                } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            } finally {
                executor.shutdownNow();
            }
        });
        ProjectScope.clear();
    }
}
