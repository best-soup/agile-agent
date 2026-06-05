package com.example.agileagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class AgentConfig {

    private static final String PROFILE_KEY_PREFIX = "user:profile:";

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
        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object memoryId) {
                ChatMemory inner = new RedisChatMemory(memoryId, redisTemplate, objectMapper,
                        maxMessages, ttlSeconds, keyPrefix);

                // 装饰器：在消息列表前自动注入用户画像
                return new ChatMemoryDecorator(inner, memoryId, redisTemplate);
            }
        };
    }

    /**
     * 记忆装饰器 — 在每次读取消息时，自动从 Redis 获取用户画像并注入为 SystemMessage。
     * 画像不参与消息窗口淘汰，永久保留。
     */
    private static class ChatMemoryDecorator implements ChatMemory {

        private final ChatMemory delegate;
        private final String profileKey;

        ChatMemoryDecorator(ChatMemory delegate, Object memoryId, StringRedisTemplate redis) {
            this.delegate = delegate;
            this.profileKey = PROFILE_KEY_PREFIX + memoryId;
            this.redis = redis;
        }

        private final StringRedisTemplate redis;

        @Override
        public void add(ChatMessage message) {
            delegate.add(message);
        }

        @Override
        public List<ChatMessage> messages() {
            List<ChatMessage> msgs = new ArrayList<>();
            String profile = redis.opsForValue().get(profileKey);
            if (profile != null && !profile.isBlank()) {
                msgs.add(new SystemMessage(
                        "[以下关于当前用户的已知信息，请基于这些信息提供个性化回复]\n" + profile));
            }
            msgs.addAll(delegate.messages());
            return msgs;
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Object id() {
            return delegate.id();
        }
    }
}
