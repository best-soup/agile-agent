package com.example.agileagent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ProjectMemberRequest(@NotNull(message = "成员用户 ID 不能为空") Long userId,
                                   @Pattern(regexp = "OWNER|ADMIN|MEMBER|VIEWER", message = "角色必须为 OWNER、ADMIN、MEMBER 或 VIEWER") String role) {
}
