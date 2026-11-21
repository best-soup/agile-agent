package com.example.agileagent.agent;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.service.ProjectAccessService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class UserProfileTools {

    private static final String PROFILE_KEY_PREFIX = "user:profile:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Tool("记住关于当前用户的重要信息，如姓名、角色、偏好、工作习惯等。新信息会追加到已有画像中。")
    public String rememberUserFact(
            @P("项目ID") Long projectId,
            @P("关于用户的事实信息，例如'用户名叫张三，是前端负责人'") String fact,
            @ToolMemoryId Object memoryId) {

        return withContext(memoryId, () -> {
            projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
            String key = profileKey(projectId);
            String current = redisTemplate.opsForValue().get(key);
            if (current != null && current.contains(fact)) return "已记录过：" + fact;
            String updated = (current == null || current.isBlank()) ? fact : current + "；" + fact;
            redisTemplate.opsForValue().set(key, updated);
            return "已记住用户事实：" + fact;
        });
    }

    @Tool("查看当前已记住的用户画像信息")
    public String getUserProfile(@P("项目ID") Long projectId, @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
            String profile = redisTemplate.opsForValue().get(profileKey(projectId));
            return profile == null || profile.isBlank() ? "暂无用户画像记录。" : "用户画像：" + profile;
        });
    }

    private String profileKey(Long projectId) {
        return PROFILE_KEY_PREFIX + ProjectScope.requireUserId() + ":" + projectId;
    }

    private <T> T withContext(Object memoryId, Supplier<T> action) {
        return ProjectScope.runWith(ProjectScope.contextFromMemoryId(memoryId), action);
    }
}
