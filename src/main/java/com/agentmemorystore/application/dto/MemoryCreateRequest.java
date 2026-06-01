package com.agentmemorystore.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a new memory. Only EPISODIC type is allowed in the MVP.
 */
@Schema(description = "Request payload for storing a new episodic memory")
public record MemoryCreateRequest(

        @NotBlank(message = "Content must not be blank")
        @Schema(
                description = "The textual content of the memory to store",
                example = "The user prefers dark mode and uses Java 21 for all projects"
        )
        String content
) {
}
