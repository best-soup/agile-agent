package com.example.agileagent.dto;

import com.example.agileagent.entity.TaskIssueEntity;
import java.util.List;

public record TaskPageResponse(long page, long size, long total, List<TaskIssueEntity> records) {
}
