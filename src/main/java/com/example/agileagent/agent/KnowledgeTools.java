package com.example.agileagent.agent;

import com.example.agileagent.service.RagService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeTools {

    @Autowired
    private RagService ragService;

    @Tool("搜索历史会议纪要和需求文档中的相关内容，用于回答关于历史讨论、决策记录、方案背景等知识性问题。搜索结果会携带文档标题、日期和来源信息")
    public String searchKnowledge(
            @P("项目ID") Long projectId,
            @P("搜索关键词或自然语言问题") String query) {

        List<RagService.SearchResult> results = ragService.search(projectId, query, 3);

        if (results.isEmpty()) {
            return "未在项目 " + projectId + " 的历史文档中找到与 \"" + query + "\" 相关的内容。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("在项目 ").append(projectId).append(" 的历史文档中找到以下相关内容：\n\n");
        for (int i = 0; i < results.size(); i++) {
            RagService.SearchResult r = results.get(i);
            sb.append("【片段 ").append(i + 1).append("】");
            if (r.title() != null) sb.append(" 文档:").append(r.title());
            if (r.date() != null) sb.append(" 日期:").append(r.date());
            sb.append(" 相关度:").append(String.format("%.0f%%", r.score() * 100));
            sb.append("\n");
            sb.append(r.text()).append("\n");
            if (r.source() != null) sb.append("（来源: ").append(r.source()).append("）");
            sb.append("\n\n");
        }
        return sb.toString();
    }
}
