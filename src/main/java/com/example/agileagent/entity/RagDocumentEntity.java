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
@TableName("rag_document")
public class RagDocumentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String documentId;
    private Long projectId;
    private String sourceType;
    private String sourceId;
    private String title;
    private String contentHash;
    private Integer version;
    private String status;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
