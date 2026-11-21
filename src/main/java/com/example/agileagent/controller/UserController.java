package com.example.agileagent.controller;

import com.example.agileagent.dto.CreateUserRequest;
import com.example.agileagent.dto.CreateUserResponse;
import com.example.agileagent.dto.CreateApiKeyRequest;
import com.example.agileagent.dto.ApiKeyCreatedResponse;
import com.example.agileagent.dto.ApiKeyView;
import com.example.agileagent.entity.UserEntity;
import com.example.agileagent.mapper.UserMapper;
import com.example.agileagent.service.UserAuthService;
import com.example.agileagent.service.UserApiKeyService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserMapper userMapper;
    private final UserAuthService authService;
    private final UserApiKeyService apiKeyService;

    public UserController(UserMapper userMapper, UserAuthService authService, UserApiKeyService apiKeyService) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @Transactional
    public CreateUserResponse create(@RequestBody CreateUserRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String apiKey = authService.createCredential();
        UserEntity user = UserEntity.builder()
                .username(request.username().trim())
                .displayName(request.displayName())
                .apiKeyHash(UserAuthService.sha256(apiKey))
                .createdAt(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        apiKeyService.registerInitial(user.getId(), apiKey);
        return new CreateUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), apiKey);
    }

    @PostMapping("/me/api-keys")
    public ApiKeyCreatedResponse createKey(@RequestBody(required = false) CreateApiKeyRequest request) {
        return apiKeyService.createForCurrentUser(request == null ? null : request.name(),
                request == null ? null : request.expiresAt());
    }

    @GetMapping("/me/api-keys")
    public List<ApiKeyView> listKeys() {
        return apiKeyService.listForCurrentUser();
    }

    @PostMapping("/me/api-keys/{id}/rotate")
    public ApiKeyCreatedResponse rotateKey(@PathVariable Long id,
                                            @RequestBody(required = false) CreateApiKeyRequest request) {
        return apiKeyService.rotateForCurrentUser(id, request == null ? null : request.name(),
                request == null ? null : request.expiresAt());
    }

    @PostMapping("/me/api-keys/{id}/revoke")
    public ApiKeyView revokeKey(@PathVariable Long id) {
        return apiKeyService.revokeForCurrentUser(id);
    }

    @GetMapping("/me")
    public UserEntity get() {
        Long userId = com.example.agileagent.config.ProjectScope.requireUserId();
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        return user;
    }
}
