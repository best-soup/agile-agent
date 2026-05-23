package com.example.agileagent.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {
    /**
     * 注册一个全局的聊天记忆提供者
     * LangChain4j 会自动拦截带有 @MemoryId 的参数，并为其分配对应的记忆体
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        // withMaxMessages(10) 表示每个会话（按项目ID）保留最近的 10 条对话记录
        // 注：这里我们先用最稳妥的内存版跑通，后续只要替换这一行，就能无缝切换为 Redis 存储！
        return memoryId -> MessageWindowChatMemory.withMaxMessages(10);
    }
}
