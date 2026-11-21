package com.example.agileagent.config;

import org.slf4j.MDC;

import java.util.function.Supplier;

/** Trusted request scope shared by controllers and Agent tools. */
public final class ProjectScope {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ProjectScope() {
    }

    public static <T> T runAs(Long userId, Long projectId, Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(userId, projectId, previous == null ? null : previous.requestId()));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void authenticate(Long userId) {
        authenticate(userId, null);
    }

    public static void authenticate(Long userId, String requestId) {
        CURRENT.set(new Scope(userId, null, requestId));
    }

    public static Context capture() {
        Scope scope = CURRENT.get();
        return scope == null ? null : new Context(scope.userId(), scope.projectId(), scope.requestId());
    }

    public static <T> T runWith(Context context, Supplier<T> action) {
        Scope previous = CURRENT.get();
        if (context == null) CURRENT.remove();
        else CURRENT.set(new Scope(context.userId(), context.projectId(), context.requestId()));
        if (context == null || context.requestId() == null) MDC.remove("requestId");
        else MDC.put("requestId", context.requestId());
        try {
            return action.get();
        } finally {
            if (previous == null || previous.requestId() == null) MDC.remove("requestId");
            else MDC.put("requestId", previous.requestId());
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void runWith(Context context, Runnable action) {
        runWith(context, () -> {
            action.run();
            return null;
        });
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove("requestId");
    }

    public static <T> T runInProject(Long projectId, Supplier<T> action) {
        Scope current = CURRENT.get();
        if (current == null || current.userId() == null) {
            throw new IllegalStateException("缺少已认证用户上下文");
        }
        return runAs(current.userId(), projectId, action);
    }

    public static void runInProject(Long projectId, Runnable action) {
        runInProject(projectId, () -> {
            action.run();
            return null;
        });
    }

    public static void runAs(Long userId, Long projectId, Runnable action) {
        runAs(userId, projectId, () -> {
            action.run();
            return null;
        });
    }

    public static Long requireUserId() {
        Scope scope = CURRENT.get();
        if (scope == null || scope.userId() == null) {
            throw new IllegalStateException("缺少当前用户上下文，请通过 Authorization: Bearer <apiKey> 访问");
        }
        return scope.userId();
    }

    public static Long requireProjectId(Long requestedProjectId) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.projectId() == null) {
            throw new IllegalStateException("缺少当前项目上下文");
        }
        if (requestedProjectId == null || !scope.projectId().equals(requestedProjectId)) {
            throw new SecurityException("请求项目与当前项目上下文不一致");
        }
        return scope.projectId();
    }

    public static Long currentProjectId() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.projectId();
    }

    public static Long currentUserId() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.userId();
    }

    public static String currentRequestId() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.requestId();
    }

    public static String encodeMemoryId(Long projectId) {
        return "ctx|" + requireUserId() + "|" + projectId + "|"
                + (currentRequestId() == null ? "" : currentRequestId());
    }

    public static Context contextFromMemoryId(Object memoryId) {
        if (memoryId == null) throw new IllegalStateException("缺少 Agent 请求上下文");
        String value = memoryId.toString();
        if (!value.startsWith("ctx|")) throw new IllegalStateException("缺少 Agent 请求上下文");
        String[] parts = value.split("\\|", 4);
        if (parts.length < 4) throw new IllegalStateException("Agent 请求上下文格式无效");
        return new Context(Long.valueOf(parts[1]), Long.valueOf(parts[2]), parts[3].isBlank() ? null : parts[3]);
    }

    public static String storageMemoryId(Object memoryId) {
        if (memoryId == null) return null;
        String value = memoryId.toString();
        if (!value.startsWith("ctx|")) return value;
        Context context = contextFromMemoryId(value);
        return context.userId() + ":" + context.projectId();
    }

    public record Context(Long userId, Long projectId, String requestId) {
    }

    private record Scope(Long userId, Long projectId, String requestId) {
    }
}
