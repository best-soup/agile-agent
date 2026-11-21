package com.example.agileagent.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

public record ApiErrorResponse(String code, String message, String requestId,
                               OffsetDateTime timestamp, List<ApiFieldError> fieldErrors) {
    public ApiErrorResponse {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiErrorResponse of(String code, String message, String requestId) {
        return new ApiErrorResponse(code, message, requestId,
                OffsetDateTime.now(ZoneId.systemDefault()), List.of());
    }

    public static ApiErrorResponse of(String code, String message, String requestId,
                                      List<ApiFieldError> fieldErrors) {
        return new ApiErrorResponse(code, message, requestId,
                OffsetDateTime.now(ZoneId.systemDefault()), fieldErrors);
    }
}
