package com.example.agileagent.agent;

import com.example.agileagent.service.RagService;
import com.example.agileagent.service.ProjectAccessService;
import com.example.agileagent.config.ProjectScope;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class KnowledgeTools {

    @Autowired
    private RagService ragService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Tool("搜索历史会议纪要和需求文档中的相关内容，用于回答关于历史讨论、决策记录、方案背景等知识性问题。搜索结果会携带文档标题、日期和来源信息")
    public String searchKnowledge(
            @P("项目ID") Long projectId,
            @P("搜索关键词或自然语言问题") String query,
            @ToolMemoryId Object memoryId) {

        return withContext(memoryId, () -> search(projectId, query));
    }

    private String search(Long projectId, String query) {

        // The request scope is authoritative; a missing model argument can use it, but a mismatch is rejected.
        Long trustedProjectId = ProjectScope.currentProjectId();
        if (trustedProjectId == null) {
            throw new IllegalStateException("缺少当前项目上下文");
        }
        if (projectId != null && !trustedProjectId.equals(projectId)) {
            throw new SecurityException("请求项目与当前项目上下文不一致");
        }
        projectAccessService.requireCurrentProject(trustedProjectId, ProjectAccessService.Permission.READ);

        List<RagService.SearchResult> results = ragService.search(trustedProjectId, query, 3);

        if (results.isEmpty()) {
            return "未在项目 " + trustedProjectId + " 的历史文档中找到与 \"" + query + "\" 相关的内容。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("在项目 ").append(trustedProjectId).append(" 的历史文档中找到以下相关内容：\n\n");
        for (int i = 0; i < results.size(); i++) {
            RagService.SearchResult r = results.get(i);
            sb.append("【片段 ").append(i + 1).append("】 相关度:")
                    .append(String.format("%.0f%%", r.score() * 100)).append("\n")
                    .append("[来源] ").append(r.citationLabel()).append("\n")
                    .append("[文档ID] ").append(r.documentId() == null ? "未知" : r.documentId()).append("\n")
                    .append("[片段] ").append(r.chunkIndex() == null ? "未知" : r.chunkIndex()).append("\n")
                    .append("[内容] ").append(r.text()).append("\n\n");
        }
        return sb.toString();
    }

    private <T> T withContext(Object memoryId, Supplier<T> action) {
        return ProjectScope.runWith(ProjectScope.contextFromMemoryId(memoryId), action);
    }
}
