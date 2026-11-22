package com.example.agileagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record MeetingTextRequest(@NotBlank @Size(max = 255) String title,
                                 @NotBlank @Size(max = 5_000_000) String content,
                                 @Size(max = 255) String sourceId,
                                 @Size(max = 500) String sourceUrl,
                                 LocalDate meetingDate) { }
