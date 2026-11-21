package com.example.agileagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("task_draft")
public class TaskDraftEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long createdBy;
    private String sourceType;
    private String sourceRef;
    private String sourceContentHash;
    private String status;
    private String idempotencyKey;
    private String failureReason;
    private String submittedTaskIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime submittedAt;
}
