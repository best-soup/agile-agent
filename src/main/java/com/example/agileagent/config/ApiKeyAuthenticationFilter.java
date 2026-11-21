package com.example.agileagent.config;

import com.example.agileagent.entity.UserEntity;
import com.example.agileagent.dto.ApiErrorResponse;
import com.example.agileagent.service.UserAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final UserAuthService userAuthService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(UserAuthService userAuthService, ObjectMapper objectMapper) {
        this.userAuthService = userAuthService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && "/api/users".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            unauthorized(request, response, "AUTHENTICATION_REQUIRED", "缺少 Bearer API 凭证");
            return;
        }
        UserEntity user;
        try {
            user = userAuthService.requireUser(authorization.substring(7).trim());
        } catch (SecurityException e) {
            unauthorized(request, response, "INVALID_CREDENTIAL", "API 凭证无效");
            return;
        } catch (RuntimeException e) {
            unauthorized(request, response, "INTERNAL_ERROR", "请求处理失败", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        ProjectScope.authenticate(user.getId(), RequestIdFilter.currentId(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            ProjectScope.clear();
        }
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response,
                              String code, String message) throws IOException {
        unauthorized(request, response, code, message, HttpServletResponse.SC_UNAUTHORIZED);
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response,
                              String code, String message, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(code, message, RequestIdFilter.currentId(request)));
    }
}
