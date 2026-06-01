package com.agentmemorystore.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI configuration for the Agent Memory Store API.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI agentMemoryStoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agent Memory Store API")
                        .description("REST API providing long-term memory for AI agents using semantic search and pgvector")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Agent Memory Store")
                                .url("https://github.com/joaogabriel43/agent-memory-store")));
    }
}
