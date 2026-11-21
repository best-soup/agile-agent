package com.example.agileagent.service;

public class ApiKeyNotFoundException extends RuntimeException {
    public ApiKeyNotFoundException() {
        super("API Key 不存在");
    }
}
