package com.example.agileagent.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agileagent.entity.TaskIssueEntity;
import com.example.agileagent.mapper.TaskIssueMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class TaskTools {

    @Autowired
    private TaskIssueMapper taskIssueMapper;

    @Tool("查询指定项目下的所有工单任务，返回工单列表（含ID、标题、负责人、优先级、截止日期、完成状态）")
    public String queryTasks(@P("项目ID") Long projectId) {
        List<TaskIssueEntity> tasks = taskIssueMapper.selectList(
                new LambdaQueryWrapper<TaskIssueEntity>()
                        .eq(TaskIssueEntity::getProjectId, projectId)
        );
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

        TaskIssueEntity entity = TaskIssueEntity.builder()
                .projectId(projectId)
                .title(title)
                .assigneeName(assigneeName)
                .description(description)
                .priority("MEDIUM")
                .isCompleted(0)
                .build();

        taskIssueMapper.insert(entity);
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

        LocalDate deadline = null;
        try {
            deadline = LocalDate.parse(deadlineStr);
        } catch (Exception ignored) {
        }

        TaskIssueEntity entity = TaskIssueEntity.builder()
                .projectId(projectId)
                .title(title)
                .assigneeName(assigneeName)
                .description(description)
                .priority(priority)
                .deadline(deadline)
                .isCompleted(0)
                .build();

        taskIssueMapper.insert(entity);
        return "已创建工单 [ID:" + entity.getId() + "] " + title
                + "，优先级：" + priority + "，截止：" + deadlineStr;
    }

    @Tool("更新指定工单的优先级")
    public String updateTaskPriority(
            @P("工单ID") Long taskId,
            @P("新优先级 HIGH/MEDIUM/LOW") String newPriority) {

        TaskIssueEntity entity = taskIssueMapper.selectById(taskId);
        if (entity == null) {
            return "未找到 ID 为 " + taskId + " 的工单。";
        }
        entity.setPriority(newPriority);
        taskIssueMapper.updateById(entity);
        return "已将工单 [ID:" + taskId + "] " + entity.getTitle()
                + " 的优先级更新为 " + newPriority;
    }

    @Tool("将指定工单标记为已完成")
    public String markTaskComplete(@P("工单ID") Long taskId) {
        TaskIssueEntity entity = taskIssueMapper.selectById(taskId);
        if (entity == null) {
            return "未找到 ID 为 " + taskId + " 的工单。";
        }
        entity.setIsCompleted(1);
        taskIssueMapper.updateById(entity);
        return "已将工单 [ID:" + taskId + "] " + entity.getTitle() + " 标记为已完成。";
    }

    @Tool("删除指定工单")
    public String deleteTask(@P("工单ID") Long taskId) {
        TaskIssueEntity entity = taskIssueMapper.selectById(taskId);
        if (entity == null) {
            return "未找到 ID 为 " + taskId + " 的工单。";
        }
        String title = entity.getTitle();
        taskIssueMapper.deleteById(taskId);
        return "已删除工单 [ID:" + taskId + "] " + title + "。";
    }

    @Tool("按负责人姓名查询项目下的工单，返回该负责人名下的所有工单")
    public String queryByAssignee(
            @P("项目ID") Long projectId,
            @P("负责人姓名") String assigneeName) {
        List<TaskIssueEntity> tasks = taskIssueMapper.selectList(
                new LambdaQueryWrapper<TaskIssueEntity>()
                        .eq(TaskIssueEntity::getProjectId, projectId)
                        .eq(TaskIssueEntity::getAssigneeName, assigneeName)
        );
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
}
