package com.example.agileagent.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserProfileTools {

    private static final String PROFILE_KEY_PREFIX = "user:profile:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Tool("记住关于当前用户的重要信息，如姓名、角色、偏好、工作习惯等。新信息会追加到已有画像中。")
    public String rememberUserFact(
            @P("项目ID") Long projectId,
            @P("关于用户的事实信息，例如'用户名叫张三，是前端负责人'") String fact) {

        String key = PROFILE_KEY_PREFIX + projectId;
        String current = redisTemplate.opsForValue().get(key);
        if (current != null && current.contains(fact)) {
            return "已记录过：" + fact;
        }
        String updated = (current == null || current.isBlank()) ? fact : current + "；" + fact;
        redisTemplate.opsForValue().set(key, updated);
        return "已记住用户事实：" + fact;
    }

    @Tool("查看当前已记住的用户画像信息")
    public String getUserProfile(@P("项目ID") Long projectId) {
        String key = PROFILE_KEY_PREFIX + projectId;
        String profile = redisTemplate.opsForValue().get(key);
        if (profile == null || profile.isBlank()) {
            return "暂无用户画像记录。";
        }
        return "用户画像：" + profile;
    }
}
