package com.example.agileagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("meeting_record")
public class MeetingRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String sourceType;
    private String sourceId;
    private String identityHash;
    private String title;
    private String content;
    private String contentHash;
    private String sourceUrl;
    private LocalDate meetingDate;
    private Long importedBy;
    private String indexStatus;
    private String indexFailureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
