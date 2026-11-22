package com.example.agileagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("Agile Agent API").version("1.0")
                .description("项目、会议记录、任务草稿及知识检索 API"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("API Key")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    OpenApiCustomizer apiResponses() {
        return api -> api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
            operation.getResponses().addApiResponse("400", new ApiResponse().description("请求参数无效"));
            operation.getResponses().addApiResponse("401", new ApiResponse().description("认证失败"));
            operation.getResponses().addApiResponse("403", new ApiResponse().description("项目权限不足"));
            operation.getResponses().addApiResponse("404", new ApiResponse().description("资源不存在"));
            operation.getResponses().addApiResponse("409", new ApiResponse().description("资源或状态冲突"));
            operation.getResponses().addApiResponse("500", new ApiResponse().description("服务器处理失败"));
            if ("/api/users".equals(path) && operation == item.getPost()) operation.setSecurity(java.util.List.of());
        }));
    }
}
