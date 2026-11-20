package com.example.agileagent.agent;

import com.example.agileagent.dto.CreateTaskRequest;
import com.example.agileagent.entity.TaskIssueEntity;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.service.TaskIssueService;
import com.example.agileagent.service.TaskNotFoundException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskTools {

    @Autowired
    private TaskIssueService taskIssueService;

    @Tool("查询指定项目下的所有工单任务，返回工单列表（含ID、标题、负责人、优先级、截止日期、完成状态）")
    public String queryTasks(@P("项目ID") Long projectId) {
        List<TaskIssueEntity> tasks = taskIssueService.listAllTasks(projectId, null);
        if (tasks.isEmpty()) {
            return "项目 " + projectId + " 下暂无工单。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("项目 ").append(projectId).append(" 的工单列表：\n");
        for (TaskIssueEntity t : tasks) {
            String status = t.getIsCompleted() != null && t.getIsCompleted() == 1 ? "已完成" : "未完成";
            sb.append("- [ID:").append(t.getId()).append("] ").append(t.getTitle())
                    .append(" | 负责人:").append(t.getAssigneeName())
                    .append(" | 优先级:").append(t.getPriority())
                    .append(" | 截止:").append(t.getDeadline())
                    .append(" | ").append(status).append("\n");
        }
        return sb.toString();
    }

    @Tool("在指定项目下创建新的工单任务，返回创建成功的工单ID")
    public String createTask(
            @P("项目ID") Long projectId,
            @P("任务标题") String title,
            @P("负责人姓名") String assigneeName,
            @P("任务描述") String description) {

        TaskIssueEntity entity = taskIssueService.createTask(projectId,
                new CreateTaskRequest(title, assigneeName, description, "MEDIUM", null));
        return "已创建工单 [ID:" + entity.getId() + "] " + title + "，负责人：" + assigneeName;
    }

    @Tool("创建带完整字段的工单任务（含优先级和截止日期）")
    public String createTaskFull(
            @P("项目ID") Long projectId,
            @P("任务标题") String title,
            @P("负责人姓名") String assigneeName,
            @P("任务描述") String description,
            @P("优先级 HIGH/MEDIUM/LOW") String priority,
            @P("截止日期 YYYY-MM-DD") String deadlineStr) {

        TaskIssueEntity entity = taskIssueService.createTask(projectId,
                new CreateTaskRequest(title, assigneeName, description, priority, deadlineStr));
        return "已创建工单 [ID:" + entity.getId() + "] " + title
                + "，优先级：" + priority + "，截止：" + deadlineStr;
    }

    @Tool("更新指定工单的优先级")
    public String updateTaskPriority(
            @P("工单ID") Long taskId,
            @P("新优先级 HIGH/MEDIUM/LOW") String newPriority) {

        TaskIssueEntity entity;
        try {
            entity = taskIssueService.updatePriority(currentProject(), taskId, newPriority);
        } catch (TaskNotFoundException e) {
            return "未找到 ID 为 " + taskId + " 的工单。";
        }
        return "已将工单 [ID:" + taskId + "] " + entity.getTitle()
                + " 的优先级更新为 " + newPriority;
    }

    @Tool("将指定工单标记为已完成")
    public String markTaskComplete(@P("工单ID") Long taskId) {
        TaskIssueEntity entity;
        try {
            entity = taskIssueService.setCompleted(currentProject(), taskId, true);
        } catch (TaskNotFoundException e) {
            return "未找到 ID 为 " + taskId + " 的工单。";
        }
        return "已将工单 [ID:" + taskId + "] " + entity.getTitle() + " 标记为已完成。";
    }

    @Tool("删除指定工单")
    public String deleteTask(@P("工单ID") Long taskId) {
        TaskIssueEntity entity;
        try {
            entity = taskIssueService.deleteTask(currentProject(), taskId);
        } catch (TaskNotFoundException e) {
            return "未找到 ID 为 " + taskId + " 的工单。";
        }
        String title = entity.getTitle();
        return "已删除工单 [ID:" + taskId + "] " + title + "。";
    }

    @Tool("按负责人姓名查询项目下的工单，返回该负责人名下的所有工单")
    public String queryByAssignee(
            @P("项目ID") Long projectId,
            @P("负责人姓名") String assigneeName) {
        List<TaskIssueEntity> tasks = taskIssueService.listAllTasks(projectId, assigneeName);
        if (tasks.isEmpty()) {
            return "项目 " + projectId + " 中未找到 " + assigneeName + " 负责的工单。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(assigneeName).append(" 在项目 ").append(projectId).append(" 中负责的工单：\n");
        for (TaskIssueEntity t : tasks) {
            String status = t.getIsCompleted() != null && t.getIsCompleted() == 1 ? "已完成" : "未完成";
            sb.append("- [ID:").append(t.getId()).append("] ").append(t.getTitle())
                    .append(" | 优先级:").append(t.getPriority())
                    .append(" | 截止:").append(t.getDeadline())
                    .append(" | ").append(status).append("\n");
        }
        return sb.toString();
    }

    private Long currentProject() {
        Long projectId = ProjectScope.currentProjectId();
        if (projectId == null) {
            throw new IllegalStateException("缺少当前项目上下文");
        }
        return projectId;
    }
}
