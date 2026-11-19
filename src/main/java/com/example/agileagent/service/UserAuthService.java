package com.example.agileagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agileagent.entity.UserEntity;
import com.example.agileagent.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class UserAuthService {

    private final UserMapper userMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserAuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public String createCredential() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public UserEntity requireUser(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SecurityException("缺少 Bearer API 凭证");
        }
        String hash = sha256(apiKey);
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getApiKeyHash, hash));
        if (user == null) {
            throw new SecurityException("API 凭证无效");
        }
        return user;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
