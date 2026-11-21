package com.example.agileagent.dto;

import com.example.agileagent.entity.TaskDraftEntity;
import com.example.agileagent.entity.TaskDraftItemEntity;
import java.util.List;

public record TaskDraftResponse(TaskDraftEntity draft, List<TaskDraftItemEntity> items) {}
