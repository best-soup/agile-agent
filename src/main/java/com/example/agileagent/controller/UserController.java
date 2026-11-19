package com.example.agileagent.controller;

import com.example.agileagent.dto.CreateUserRequest;
import com.example.agileagent.dto.CreateUserResponse;
import com.example.agileagent.entity.UserEntity;
import com.example.agileagent.mapper.UserMapper;
import com.example.agileagent.service.UserAuthService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserMapper userMapper;
    private final UserAuthService authService;

    public UserController(UserMapper userMapper, UserAuthService authService) {
        this.userMapper = userMapper;
        this.authService = authService;
    }

    @PostMapping
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
        return new CreateUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), apiKey);
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
