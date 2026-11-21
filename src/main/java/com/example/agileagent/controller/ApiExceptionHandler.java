package com.example.agileagent.controller;

import com.example.agileagent.config.RequestIdFilter;
import com.example.agileagent.dto.ApiErrorResponse;
import com.example.agileagent.dto.ApiFieldError;
import com.example.agileagent.service.ApiKeyNotFoundException;
import com.example.agileagent.service.ApiKeyStateException;
import com.example.agileagent.service.DraftNotFoundException;
import com.example.agileagent.service.DraftStateException;
import com.example.agileagent.service.TaskNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> taskNotFound(TaskNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "当前项目中不存在该任务", request);
    }

    @ExceptionHandler(DraftNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> draftNotFound(DraftNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "当前项目中不存在该草稿或草稿项", request);
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> apiKeyNotFound(ApiKeyNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API Key 不存在", request);
    }

    @ExceptionHandler({DraftStateException.class, ApiKeyStateException.class})
    public ResponseEntity<ApiErrorResponse> stateConflict(RuntimeException e, HttpServletRequest request) {
        String code = e instanceof ApiKeyStateException ? "API_KEY_STATE_CONFLICT" : "DRAFT_STATE_CONFLICT";
        return error(HttpStatus.CONFLICT, code, e.getMessage(), request);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiErrorResponse> forbidden(SecurityException e, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前用户无权执行该操作", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> authenticationRequired(IllegalStateException e, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "缺少已认证用户上下文", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> methodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiFieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(field -> new ApiFieldError(field.getField(), field.getDefaultMessage())).toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> constraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        List<ApiFieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new ApiFieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", request, fields);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingPathVariableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> requestParameter(Exception e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER", "请求参数格式或内容无效", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "请求体不是有效的 JSON", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "不支持当前 HTTP 方法", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "不支持当前 Content-Type", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception e, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器处理请求失败", request);
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return error(status, code, message, request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message,
                                                   HttpServletRequest request, List<ApiFieldError> fields) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message,
                RequestIdFilter.currentId(request), fields));
    }
}
