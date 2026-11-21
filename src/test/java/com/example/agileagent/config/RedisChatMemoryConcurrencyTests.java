package com.example.agileagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisChatMemoryConcurrencyTests {
    @Test
    void concurrentAddsUseAtomicAppendAndKeepWindowBounded() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        List<String> storage = new ArrayList<>();
        AtomicBoolean lock = new AtomicBoolean(false);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        when(lists.range(anyString(), eq(0L), eq(-1L))).thenAnswer(invocation -> new ArrayList<>(storage));
        when(lists.range(anyString(), eq(0L), eq(0L))).thenAnswer(invocation -> storage.isEmpty() ? List.of() : List.of(storage.get(0)));
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> lock.compareAndSet(false, true));
        org.mockito.stubbing.Answer<Long> executeAnswer = invocation -> {
            synchronized (storage) {
                storage.add("{\"@type\":\"USER\",\"text\":\"append\"}");
                while (storage.size() > 10) storage.remove(0);
                lock.set(false);
            }
            return 1L;
        };
        doAnswer(executeAnswer).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        doAnswer(executeAnswer).when(redis).execute(any(RedisScript.class), anyList(), anyString());

        RedisChatMemory memory = new RedisChatMemory("user:1:project:10", redis, new ObjectMapper(), 10, 60, "chat:");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int worker = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 10; j++) memory.add(new UserMessage("w" + worker + "-" + j));
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(10, storage.size());
        assertEquals(10, memory.messages().size());
        verify(redis, atLeastOnce()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void legacyJsonIsReadBeforeVersionedListMigration() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        when(lists.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(values.get(contains("chat:"))).thenReturn("[{\"@type\":\"USER\",\"text\":\"legacy\"}]");
        RedisChatMemory memory = new RedisChatMemory("legacy", redis, new ObjectMapper(), 10, 60, "chat:");
        assertEquals("legacy", ((UserMessage) memory.messages().get(0)).singleText());
    }
}
