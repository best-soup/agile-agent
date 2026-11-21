package com.example.agileagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(@NotBlank(message = "标题不能为空") @Size(max = 255, message = "标题不能超过 255 个字符") String title,
                                @Size(max = 50, message = "负责人姓名不能超过 50 个字符") String assigneeName,
                                String description,
                                @Pattern(regexp = "(?i)HIGH|MEDIUM|LOW", message = "priority 必须为 HIGH、MEDIUM 或 LOW") String priority,
                                @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "deadline 必须为 YYYY-MM-DD") String deadline) {
}
