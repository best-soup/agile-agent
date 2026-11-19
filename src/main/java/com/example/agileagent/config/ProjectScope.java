package com.example.agileagent.config;

import java.util.function.Supplier;

/** Trusted request scope shared by controllers and Agent tools. */
public final class ProjectScope {

    private static final InheritableThreadLocal<Scope> CURRENT = new InheritableThreadLocal<>();

    private ProjectScope() {
    }

    public static <T> T runAs(Long userId, Long projectId, Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(userId, projectId));
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
        CURRENT.set(new Scope(userId, null));
    }

    public static void clear() {
        CURRENT.remove();
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

    private record Scope(Long userId, Long projectId) {
    }
}
