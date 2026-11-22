package com.example.agileagent.integration;

import com.example.agileagent.config.RedisChatMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisMemoryIT {
    @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Test void savesAndIsolatesMemoryIds() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        try {
            var template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var a = new RedisChatMemory("1:1:conversation-a", template, new ObjectMapper(), 10, 60, "integration:");
            var otherUser = new RedisChatMemory("2:1:conversation-a", template, new ObjectMapper(), 10, 60, "integration:");
            var otherProject = new RedisChatMemory("1:2:conversation-a", template, new ObjectMapper(), 10, 60, "integration:");
            var otherConversation = new RedisChatMemory("1:1:conversation-b", template, new ObjectMapper(), 10, 60, "integration:");
            a.add(new UserMessage("synthetic"));
            assertEquals("synthetic", ((UserMessage) a.messages().get(0)).singleText());
            assertTrue(otherUser.messages().isEmpty());
            assertTrue(otherProject.messages().isEmpty());
            assertTrue(otherConversation.messages().isEmpty());
        } finally { factory.destroy(); }
    }
}
