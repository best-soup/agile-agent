package com.example.agileagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(@NotBlank(message = "用户名不能为空") @Size(max = 100, message = "用户名不能超过 100 个字符") String username,
                               @Size(max = 100, message = "显示名称不能超过 100 个字符") String displayName) {
}
