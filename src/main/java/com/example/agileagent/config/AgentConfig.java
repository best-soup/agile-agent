package com.example.agileagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class AgentConfig {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agile.chat.memory.max-messages:10}")
    private int maxMessages;

    @Value("${agile.chat.memory.ttl-seconds:604800}")
    private long ttlSeconds;

    @Value("${agile.chat.memory.key-prefix:chat:memory:}")
    private String keyPrefix;

    public AgentConfig(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        // ===== Redis 持久化版本 =====
        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object memoryId) {
                return new RedisChatMemory(memoryId, redisTemplate, objectMapper,
                        maxMessages, ttlSeconds, keyPrefix);
            }
        };
        // ===== 纯内存版本（测试用，保留）=====
        // return new ChatMemoryProvider() {
        //     private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<>();
        //     @Override
        //     public ChatMemory get(Object memoryId) {
        //         return memories.computeIfAbsent(memoryId,
        //                 id -> MessageWindowChatMemory.withMaxMessages(maxMessages));
        //     }
        // };
    }
}
