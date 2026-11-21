package com.example.agileagent.service;

import com.example.agileagent.entity.UserEntity;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class UserAuthService {

    private final UserApiKeyService apiKeyService;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserAuthService(UserApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
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
        return apiKeyService.authenticate(apiKey);
    }

    public static String sha256(String value) {
        try {
            return UserApiKeyService.sha256(value);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
