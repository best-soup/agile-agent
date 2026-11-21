package com.example.agileagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitTaskDraftRequest(@NotBlank(message = "idempotencyKey 不能为空") @Size(max = 100, message = "idempotencyKey 不能超过 100 个字符") String idempotencyKey) {}
