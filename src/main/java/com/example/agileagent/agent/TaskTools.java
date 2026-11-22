package com.example.agileagent.agent;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.entity.TaskIssueEntity;
import com.example.agileagent.service.TaskIssueService;
import com.example.agileagent.service.TaskNotFoundException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

@Component
public class TaskTools {
    private static final Logger log = LoggerFactory.getLogger(TaskTools.class);
    @Autowired
    private TaskIssueService taskIssueService;

    @Tool("查询指定项目下的所有工单任务，返回工单列表（含ID、标题、负责人、优先级、截止日期、完成状态）")
    public String queryTasks(@P("项目ID") Long projectId, @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            List<TaskIssueEntity> tasks = taskIssueService.listAllTasks(projectId, null);
            if (tasks.isEmpty()) return "项目 " + projectId + " 下暂无工单。";
            StringBuilder sb = new StringBuilder("项目 ").append(projectId).append(" 的工单列表：\n");
            for (TaskIssueEntity t : tasks) appendTask(sb, t, true);
            return sb.toString();
        });
    }

    @Tool("在指定项目下创建新的工单任务，返回创建成功的工单ID")
    public String createTask(@P("项目ID") Long projectId, @P("任务标题") String title,
                             @P("负责人姓名") String assigneeName, @P("任务描述") String description,
                             @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            TaskIssueEntity entity = taskIssueService.createTask(projectId,
                    new CreateTaskRequest(title, assigneeName, description, "MEDIUM", null));
            return "已创建工单 [ID:" + entity.getId() + "] " + title + "，负责人：" + assigneeName;
        });
    }

    @Tool("创建带完整字段的工单任务（含优先级和截止日期）")
    public String createTaskFull(@P("项目ID") Long projectId, @P("任务标题") String title,
                                 @P("负责人姓名") String assigneeName, @P("任务描述") String description,
                                 @P("优先级 HIGH/MEDIUM/LOW") String priority,
                                 @P("截止日期 YYYY-MM-DD") String deadlineStr,
                                 @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            TaskIssueEntity entity = taskIssueService.createTask(projectId,
                    new CreateTaskRequest(title, assigneeName, description, priority, deadlineStr));
            return "已创建工单 [ID:" + entity.getId() + "] " + title
                    + "，优先级：" + priority + "，截止：" + deadlineStr;
        });
    }

    @Tool("更新指定工单的优先级")
    public String updateTaskPriority(@P("工单ID") Long taskId,
                                     @P("新优先级 HIGH/MEDIUM/LOW") String newPriority,
                                     @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            try {
                TaskIssueEntity entity = taskIssueService.updatePriority(currentProject(), taskId, newPriority);
                return "已将工单 [ID:" + taskId + "] " + entity.getTitle() + " 的优先级更新为 " + newPriority;
            } catch (TaskNotFoundException e) {
                return "未找到 ID 为 " + taskId + " 的工单。";
            }
        });
    }

    @Tool("将指定工单标记为已完成")
    public String markTaskComplete(@P("工单ID") Long taskId, @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            try {
                TaskIssueEntity entity = taskIssueService.setCompleted(currentProject(), taskId, true);
                return "已将工单 [ID:" + taskId + "] " + entity.getTitle() + " 标记为已完成。";
            } catch (TaskNotFoundException e) {
                return "未找到 ID 为 " + taskId + " 的工单。";
            }
        });
    }

    @Tool("删除指定工单")
    public String deleteTask(@P("工单ID") Long taskId, @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            try {
                TaskIssueEntity entity = taskIssueService.deleteTask(currentProject(), taskId);
                return "已删除工单 [ID:" + taskId + "] " + entity.getTitle() + "。";
            } catch (TaskNotFoundException e) {
                return "未找到 ID 为 " + taskId + " 的工单。";
            }
        });
    }

    @Tool("按负责人姓名查询项目下的工单，返回该负责人名下的所有工单")
    public String queryByAssignee(@P("项目ID") Long projectId, @P("负责人姓名") String assigneeName,
                                  @ToolMemoryId Object memoryId) {
        return withContext(memoryId, () -> {
            List<TaskIssueEntity> tasks = taskIssueService.listAllTasks(projectId, assigneeName);
            if (tasks.isEmpty()) return "项目 " + projectId + " 中未找到 " + assigneeName + " 负责的工单。";
            StringBuilder sb = new StringBuilder(assigneeName).append(" 在项目 ").append(projectId).append(" 中负责的工单：\n");
            for (TaskIssueEntity t : tasks) appendTask(sb, t, false);
            return sb.toString();
        });
    }

    private void appendTask(StringBuilder sb, TaskIssueEntity task, boolean includeAssignee) {
        String status = task.getIsCompleted() != null && task.getIsCompleted() == 1 ? "已完成" : "未完成";
        sb.append("- [ID:").append(task.getId()).append("] ").append(task.getTitle());
        if (includeAssignee) sb.append(" | 负责人:").append(task.getAssigneeName());
        sb.append(" | 优先级:").append(task.getPriority()).append(" | 截止:").append(task.getDeadline())
                .append(" | ").append(status).append("\n");
    }

    private <T> T withContext(Object memoryId, Supplier<T> action) {
        ProjectScope.Context context = ProjectScope.contextFromMemoryId(memoryId);
        return ProjectScope.runWith(context, () -> {
            log.info("Agent tool context restored projectId={} requestId={} userId={}",
                    context.projectId(), context.requestId(), context.userId());
            return action.get();
        });
    }

    private Long currentProject() {
        Long projectId = ProjectScope.currentProjectId();
        if (projectId == null) throw new IllegalStateException("缺少当前项目上下文");
        return projectId;
    }
}
