package com.example.agileagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.ApiKeyCreatedResponse;
import com.example.agileagent.dto.ApiKeyView;
import com.example.agileagent.entity.ApiKeyEntity;
import com.example.agileagent.entity.UserEntity;
import com.example.agileagent.mapper.ApiKeyMapper;
import com.example.agileagent.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

@Service
public class UserApiKeyService {
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";
    public static final String EXPIRED = "EXPIRED";

    private final ApiKeyMapper apiKeyMapper;
    private final UserMapper userMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserApiKeyService(ApiKeyMapper apiKeyMapper, UserMapper userMapper) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
    }

    @Transactional
    public ApiKeyCreatedResponse createForCurrentUser(String name, String expiresAt) {
        return create(ProjectScope.requireUserId(), name, expiresAt, null);
    }

    @Transactional
    public ApiKeyCreatedResponse registerInitial(Long userId, String plainKey) {
        return create(userId, "default", null, plainKey);
    }

    public List<ApiKeyView> listForCurrentUser() {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKeyEntity>()
                        .eq(ApiKeyEntity::getUserId, ProjectScope.requireUserId())
                        .orderByDesc(ApiKeyEntity::getId))
                .stream().map(ApiKeyView::from).toList();
    }

    @Transactional
    public ApiKeyCreatedResponse rotateForCurrentUser(Long id, String name, String expiresAt) {
        Long userId = ProjectScope.requireUserId();
        ApiKeyEntity old = ownedKey(userId, id);
        refreshExpired(old);
        if (!ACTIVE.equals(old.getStatus())) {
            throw new ApiKeyStateException("已撤销或已过期的 API Key 不能轮换");
        }
        revoke(old);
        return create(userId, name == null || name.isBlank() ? old.getName() : name, expiresAt, null);
    }

    @Transactional
    public ApiKeyView revokeForCurrentUser(Long id) {
        ApiKeyEntity key = ownedKey(ProjectScope.requireUserId(), id);
        refreshExpired(key);
        if (REVOKED.equals(key.getStatus())) {
            throw new ApiKeyStateException("API Key 已撤销");
        }
        if (EXPIRED.equals(key.getStatus())) {
            throw new ApiKeyStateException("API Key 已过期");
        }
        revoke(key);
        return ApiKeyView.from(key);
    }

    public UserEntity authenticate(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            throw new SecurityException("API 凭证无效");
        }
        String hash = sha256(plainKey);
        ApiKeyEntity key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getKeyHash, hash).eq(ApiKeyEntity::getStatus, ACTIVE));
        if (key == null) throw new SecurityException("API 凭证无效");
        if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(LocalDateTime.now())) {
            try {
                apiKeyMapper.update(null, new LambdaUpdateWrapper<ApiKeyEntity>()
                        .eq(ApiKeyEntity::getId, key.getId()).eq(ApiKeyEntity::getStatus, ACTIVE)
                        .set(ApiKeyEntity::getStatus, EXPIRED));
            } catch (RuntimeException ignored) {
                // Expiry bookkeeping must not turn an invalid credential into a server error.
            }
            throw new SecurityException("API 凭证无效");
        }
        try {
            apiKeyMapper.update(null, new LambdaUpdateWrapper<ApiKeyEntity>()
                    .eq(ApiKeyEntity::getId, key.getId()).eq(ApiKeyEntity::getStatus, ACTIVE)
                    .set(ApiKeyEntity::getLastUsedAt, LocalDateTime.now()));
        } catch (RuntimeException ignored) {
            // last_used_at is observability metadata and must not block authentication.
        }
        UserEntity user = userMapper.selectById(key.getUserId());
        if (user == null) throw new SecurityException("API 凭证无效");
        return user;
    }

    private ApiKeyCreatedResponse create(Long userId, String name, String expiresAt, String suppliedKey) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) throw new SecurityException("用户不存在: " + userId);
        String normalizedName = name == null || name.isBlank() ? "default" : name.trim();
        if (normalizedName.length() > 100) throw new IllegalArgumentException("API Key 名称不能超过 100 个字符");
        LocalDateTime expiry = parseExpiry(expiresAt);
        String plain = suppliedKey == null ? generate() : suppliedKey;
        ApiKeyEntity key = ApiKeyEntity.builder().userId(userId).name(normalizedName)
                .keyPrefix(prefix(plain)).keyHash(sha256(plain)).status(ACTIVE)
                .expiresAt(expiry).createdAt(LocalDateTime.now()).build();
        apiKeyMapper.insert(key);
        return new ApiKeyCreatedResponse(ApiKeyView.from(key), plain);
    }

    private ApiKeyEntity ownedKey(Long userId, Long id) {
        ApiKeyEntity key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getId, id).eq(ApiKeyEntity::getUserId, userId));
        if (key == null) throw new ApiKeyNotFoundException();
        return key;
    }

    private void revoke(ApiKeyEntity key) {
        LocalDateTime now = LocalDateTime.now();
        apiKeyMapper.update(null, new LambdaUpdateWrapper<ApiKeyEntity>()
                .eq(ApiKeyEntity::getId, key.getId()).eq(ApiKeyEntity::getUserId, key.getUserId())
                .set(ApiKeyEntity::getStatus, REVOKED).set(ApiKeyEntity::getRevokedAt, now));
        key.setStatus(REVOKED);
        key.setRevokedAt(now);
    }

    private void refreshExpired(ApiKeyEntity key) {
        if (ACTIVE.equals(key.getStatus()) && key.getExpiresAt() != null
                && !key.getExpiresAt().isAfter(LocalDateTime.now())) {
            apiKeyMapper.update(null, new LambdaUpdateWrapper<ApiKeyEntity>().eq(ApiKeyEntity::getId, key.getId())
                    .set(ApiKeyEntity::getStatus, EXPIRED));
            key.setStatus(EXPIRED);
        }
    }

    private LocalDateTime parseExpiry(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDateTime parsed = LocalDateTime.parse(value.trim());
            if (!parsed.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("过期时间必须晚于当前时间");
            return parsed;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("过期时间必须是 ISO-8601 日期时间，例如 2027-01-01T00:00:00", exception);
        }
    }

    private String generate() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "agk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String prefix(String plain) { return plain.substring(0, Math.min(12, plain.length())); }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
