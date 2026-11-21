package com.example.agileagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RedisChatMemory implements ChatMemory {

    private final String redisKey;
    private final String listKey;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxMessages;
    private final long ttlSeconds;
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], ARGV[2], '-1')
            if tonumber(ARGV[3]) > 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end
            """, Long.class);

    public RedisChatMemory(Object memoryId, StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper, int maxMessages, long ttlSeconds,
                           String keyPrefix) {
        this.redisKey = keyPrefix + memoryId;
        this.listKey = this.redisKey + ":v2";
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxMessages = maxMessages;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void add(ChatMessage message) {
        try {
            initializeListFromLegacyIfNeeded();
            String serialized = objectMapper.writeValueAsString(serializeMessage(message));
            redisTemplate.execute(APPEND_SCRIPT, List.of(listKey), serialized,
                    Integer.toString(-Math.max(1, maxMessages)), "-1", Long.toString(ttlSeconds));
        } catch (Exception e) {
            throw new RuntimeException("Failed to append chat memory to Redis", e);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        try {
            List<String> entries = redisTemplate.opsForList().range(listKey, 0, -1);
            if (entries == null || entries.isEmpty()) {
                String legacyJson = redisTemplate.opsForValue().get(redisKey);
                if (legacyJson == null || legacyJson.isEmpty()) return new ArrayList<>();
                entries = legacyEntries(legacyJson);
            }
            List<ChatMessage> messages = new ArrayList<>();
            for (String entry : entries) {
                messages.add(deserializeMessage((ObjectNode) objectMapper.readTree(entry)));
            }
            return messages;
        } catch (Exception e) {
            System.err.println("[RedisChatMemory] 反序列化失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void clear() {
        redisTemplate.delete(List.of(redisKey, listKey, listKey + ":init-lock"));
    }

    private void initializeListFromLegacyIfNeeded() throws Exception {
        List<String> current = redisTemplate.opsForList().range(listKey, 0, 0);
        if (current != null && !current.isEmpty()) return;
        String lockKey = listKey + ":init-lock";
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(10));
        if (!Boolean.TRUE.equals(acquired)) {
            for (int i = 0; i < 20; i++) {
                current = redisTemplate.opsForList().range(listKey, 0, 0);
                if (current != null && !current.isEmpty()) return;
                Thread.sleep(10);
            }
            return;
        }
        try {
            current = redisTemplate.opsForList().range(listKey, 0, 0);
            if (current != null && !current.isEmpty()) return;
            String legacyJson = redisTemplate.opsForValue().get(redisKey);
            if (legacyJson != null && !legacyJson.isEmpty()) {
                List<String> entries = legacyEntries(legacyJson);
                if (!entries.isEmpty()) {
                    redisTemplate.opsForList().rightPushAll(listKey, entries);
                    redisTemplate.opsForList().trim(listKey, Math.max(0, entries.size() - maxMessages), -1);
                    if (ttlSeconds > 0) redisTemplate.expire(listKey, ttlSeconds, TimeUnit.SECONDS);
                }
                redisTemplate.delete(redisKey);
            }
        } finally {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    private List<String> legacyEntries(String json) throws Exception {
        ArrayNode array = (ArrayNode) objectMapper.readTree(json);
        List<String> entries = new ArrayList<>();
        for (JsonNode node : array) entries.add(objectMapper.writeValueAsString(node));
        return entries;
    }

    private ObjectNode serializeMessage(ChatMessage msg) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("@type", msg.type().name());

        if (msg instanceof UserMessage um) {
            String t = um.singleText();
            node.put("text", t != null ? t : "");

        } else if (msg instanceof SystemMessage sm) {
            String t = sm.text();
            node.put("text", t != null ? t : "");

        } else if (msg instanceof AiMessage am) {
            String t = am.text();
            node.put("text", t != null ? t : "");
            if (am.hasToolExecutionRequests()) {
                ArrayNode toolCalls = objectMapper.createArrayNode();
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    ObjectNode tc = objectMapper.createObjectNode();
                    tc.put("id", req.id());
                    tc.put("name", req.name());
                    tc.put("arguments", req.arguments());
                    toolCalls.add(tc);
                }
                node.set("toolCalls", toolCalls);
            }

        } else if (msg instanceof ToolExecutionResultMessage tm) {
            String tid = tm.id();
            String tn = tm.toolName();
            String tt = tm.text();
            node.put("id", tid != null ? tid : "");
            node.put("toolName", tn != null ? tn : "");
            node.put("toolText", tt != null ? tt : "");
        }
        return node;
    }

    private ChatMessage deserializeMessage(ObjectNode node) {
        String type = node.get("@type").asText();

        switch (type) {
            case "USER": {
                String text = (node.has("text") && !node.get("text").isNull())
                        ? node.get("text").asText() : "";
                return new UserMessage(text);
            }
            case "SYSTEM": {
                String text = (node.has("text") && !node.get("text").isNull())
                        ? node.get("text").asText() : "";
                return new SystemMessage(text);
            }
            case "AI": {
                String text = (node.has("text") && !node.get("text").isNull())
                        ? node.get("text").asText() : "";
                if (node.has("toolCalls")) {
                    if (text.isEmpty()) text = null;
                    List<ToolExecutionRequest> toolCalls = new ArrayList<>();
                    for (JsonNode tc : node.get("toolCalls")) {
                        toolCalls.add(ToolExecutionRequest.builder()
                                .id(tc.get("id").asText())
                                .name(tc.get("name").asText())
                                .arguments(tc.get("arguments").asText())
                                .build());
                    }
                    return new AiMessage(text, toolCalls);
                } else {
                    if (text.isEmpty()) text = "[empty]";
                    return new AiMessage(text);
                }
            }
            case "TOOL_EXECUTION_RESULT": {
                String id = (node.has("id") && !node.get("id").isNull())
                        ? node.get("id").asText() : "";
                String toolName = (node.has("toolName") && !node.get("toolName").isNull())
                        ? node.get("toolName").asText() : "";
                String text = (node.has("toolText") && !node.get("toolText").isNull())
                        ? node.get("toolText").asText() : "";
                return new ToolExecutionResultMessage(id, toolName, text);
            }
            default:
                throw new IllegalStateException("Unknown message type in Redis: " + type);
        }
    }

    @Override
    public Object id() {
        return redisKey;
    }
}
