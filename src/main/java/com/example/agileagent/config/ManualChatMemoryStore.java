package com.example.agileagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 手写 ReAct 使用的独立 Redis 记忆存储。
 *
 * 与 @AiService 使用不同的 key 前缀，便于并行测试，避免两套执行器
 * 相互写入 Tool 消息和对话上下文。
 */
@Component
public class ManualChatMemoryStore {

    private static final String PROFILE_KEY_PREFIX = "user:profile:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxMessages;
    private final long ttlSeconds;
    private final String keyPrefix;

    public ManualChatMemoryStore(
            StringRedisTemplate redisTemplate,
            @Value("${agile.chat.memory.max-messages:20}") int maxMessages,
            @Value("${agile.chat.memory.ttl-seconds:604800}") long ttlSeconds,
            @Value("${agile.chat.memory.manual-key-prefix:chat:memory:manual:}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.maxMessages = maxMessages;
        this.ttlSeconds = ttlSeconds;
        this.keyPrefix = keyPrefix;
    }

    public ChatMemory get(Long projectId) {
        return new RedisChatMemory(
                projectId, redisTemplate, objectMapper, maxMessages, ttlSeconds, keyPrefix);
    }

    public String getUserProfile(Long projectId) {
        return redisTemplate.opsForValue().get(PROFILE_KEY_PREFIX + projectId);
    }
}
