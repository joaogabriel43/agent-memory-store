package com.agentmemorystore.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO representing a stored memory.
 * The {@code relevanceScore} field is only populated when returned from a search operation.
 */
@Schema(description = "Response representing a stored memory")
public record MemoryResponse(

        @Schema(description = "Unique identifier of the memory", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Tenant identifier that owns this memory", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID tenantId,

        @Schema(description = "Textual content of the memory", example = "The user prefers dark mode and uses Java 21 for all projects")
        String content,

        @Schema(description = "Type of the memory (EPISODIC, SEMANTIC, PROCEDURAL)", example = "EPISODIC")
        String memoryType,

        @Schema(description = "IDs of source memories that were consolidated into this one")
        List<UUID> sourceMemoryIds,

        @Schema(description = "Timestamp of the last time this memory was accessed")
        Instant lastAccessedAt,

        @Schema(description = "Timestamp when this memory was created")
        Instant createdAt,

        @Schema(description = "Relevance score from semantic search (only populated during search)", example = "0.87")
        Double relevanceScore
) {
}
