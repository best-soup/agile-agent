package com.example.agileagent.dto;

import com.example.agileagent.entity.ApiKeyEntity;

import java.time.LocalDateTime;

public record ApiKeyView(Long id, String name, String prefix, String status,
                         LocalDateTime createdAt, LocalDateTime expiresAt,
                         LocalDateTime revokedAt, LocalDateTime lastUsedAt) {
    public static ApiKeyView from(ApiKeyEntity key) {
        return new ApiKeyView(key.getId(), key.getName(), key.getKeyPrefix(), key.getStatus(),
                key.getCreatedAt(), key.getExpiresAt(), key.getRevokedAt(), key.getLastUsedAt());
    }
}
