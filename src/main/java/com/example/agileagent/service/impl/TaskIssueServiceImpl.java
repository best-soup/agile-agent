package com.example.agileagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.agileagent.dto.TaskIssueDTO;
import com.example.agileagent.entity.TaskIssueEntity;
import com.example.agileagent.mapper.TaskIssueMapper;
import com.example.agileagent.service.TaskIssueService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service // 告诉 Spring 这是一个业务逻辑类
public class TaskIssueServiceImpl extends ServiceImpl<TaskIssueMapper, TaskIssueEntity> implements TaskIssueService {

    @Override
    public void saveIssuesFromAi(Long projectId, List<TaskIssueDTO> aiExtractedIssues) {
        if (aiExtractedIssues == null || aiExtractedIssues.isEmpty()) {
            System.out.println("AI 没有提取到任何任务，放弃保存。");
            return;
        }

        // 使用 Java 8 的 Stream API，将 DTO 列表优雅地转换为 Entity 列表
        List<TaskIssueEntity> entityList = aiExtractedIssues.stream().map(dto -> {
            return TaskIssueEntity.builder()
                    .projectId(projectId)
                    .title(dto.getTitle())
                    .assigneeName(dto.getAssigneeName())
                    .description(dto.getDescription())
                    .priority(dto.getPriority())
                    // 如果 AI 返回了正确的日期格式，转换它；否则先给个 null 容错
                    .deadline(dto.getDeadline() != null ? LocalDate.parse(dto.getDeadline()) : null)
                    .isCompleted(0) // 刚创建的工单默认未完成
                    .build();
        }).collect(Collectors.toList());

        // 调用 MyBatis-Plus 自带的批量保存方法
        this.saveBatch(entityList);
        System.out.println("成功将 " + entityList.size() + " 条 AI 提取的工单写入 MySQL！");

    }
}
