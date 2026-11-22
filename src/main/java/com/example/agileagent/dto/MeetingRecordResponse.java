package com.example.agileagent.dto;

import com.example.agileagent.entity.MeetingRecordEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record MeetingRecordResponse(Long id, Long projectId, String sourceType, String sourceId,
        String title, String content, String contentHash, String sourceUrl, LocalDate meetingDate,
        Long importedBy, String indexStatus, String indexFailureReason,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static MeetingRecordResponse from(MeetingRecordEntity r, boolean includeContent) {
        return new MeetingRecordResponse(r.getId(), r.getProjectId(), r.getSourceType(), r.getSourceId(),
                r.getTitle(), includeContent ? r.getContent() : null, r.getContentHash(), r.getSourceUrl(),
                r.getMeetingDate(), r.getImportedBy(), r.getIndexStatus(), r.getIndexFailureReason(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
