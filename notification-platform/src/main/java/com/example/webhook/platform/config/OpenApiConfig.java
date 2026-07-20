package com.example.webhook.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI webhookOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Reliable Webhook Delivery Platform API")
                        .version("0.1.0")
                        .description("Event intake, reliable delivery, retry, dead-letter replay and endpoint management."))
                .components(new Components()
                        .addSecuritySchemes("AppId", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-App-Id"))
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")))
                .addSecurityItem(new SecurityRequirement().addList("AppId").addList("ApiKey"));
    }
}
