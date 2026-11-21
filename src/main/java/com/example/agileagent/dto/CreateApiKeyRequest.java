package com.example.agileagent.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(@Size(max = 100, message = "API Key 名称不能超过 100 个字符") String name,
                                  @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?", message = "过期时间必须是 ISO-8601 日期时间") String expiresAt) {
}
