package com.example.agileagent.config;

import com.example.agileagent.entity.UserEntity;
import com.example.agileagent.service.UserAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final UserAuthService userAuthService;

    public ApiKeyAuthenticationFilter(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
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
            unauthorized(response, "缺少 Bearer API 凭证");
            return;
        }
        UserEntity user;
        try {
            user = userAuthService.requireUser(authorization.substring(7).trim());
        } catch (SecurityException e) {
            unauthorized(response, e.getMessage());
            return;
        }
        ProjectScope.authenticate(user.getId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            ProjectScope.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
