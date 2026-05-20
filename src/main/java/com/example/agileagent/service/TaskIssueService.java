package com.example.agileagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.agileagent.dto.TaskIssueDTO;
import com.example.agileagent.entity.TaskIssueEntity;

import java.util.List;

public interface TaskIssueService extends IService<TaskIssueEntity> {

    // 自定义一个方法：专门用来把 AI 提取的 DTO 列表，批量存入 MySQL
    void saveIssuesFromAi(Long projectId, List<TaskIssueDTO> aiExtractedIssues);
}