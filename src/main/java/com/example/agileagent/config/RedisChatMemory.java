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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RedisChatMemory implements ChatMemory {

    private final String redisKey;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxMessages;
    private final long ttlSeconds;

    public RedisChatMemory(Object memoryId, StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper, int maxMessages, long ttlSeconds,
                           String keyPrefix) {
        this.redisKey = keyPrefix + memoryId;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxMessages = maxMessages;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = messages();
        messages.add(message);
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
        save(messages);
    }

    @Override
    public List<ChatMessage> messages() {
        String json = redisTemplate.opsForValue().get(redisKey);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            ArrayNode array = (ArrayNode) objectMapper.readTree(json);
            List<ChatMessage> messages = new ArrayList<>();
            for (JsonNode node : array) {
                messages.add(deserializeMessage((ObjectNode) node));
            }
            return messages;
        } catch (Exception e) {
            System.err.println("[RedisChatMemory] 反序列化失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void clear() {
        redisTemplate.delete(redisKey);
    }

    private void save(List<ChatMessage> messages) {
        try {
            ArrayNode array = objectMapper.createArrayNode();
            for (ChatMessage msg : messages) {
                array.add(serializeMessage(msg));
            }
            String json = objectMapper.writeValueAsString(array);
            if (ttlSeconds > 0) {
                redisTemplate.opsForValue().set(redisKey, json, ttlSeconds, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(redisKey, json);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save chat memory to Redis", e);
        }
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
