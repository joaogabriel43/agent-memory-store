package com.agentmemorystore.presentation.controller;

import com.agentmemorystore.application.dto.MemoryCreateRequest;
import com.agentmemorystore.application.dto.MemoryResponse;
import com.agentmemorystore.application.dto.MemorySearchResponse;
import com.agentmemorystore.application.dto.MemoryStatsResponse;
import com.agentmemorystore.application.usecase.MemoryUseCase;
import com.agentmemorystore.presentation.exception.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the core memory API endpoints.
 * All endpoints require the {@code X-Tenant-Id} header for multitenancy isolation (ADR-006).
 */
@RestController
@RequestMapping("/api/v1/memories")
@Tag(name = "Memories", description = "Core memory operations: store, search, and retrieve")
public class MemoryController {

    private final MemoryUseCase memoryUseCase;

    public MemoryController(MemoryUseCase memoryUseCase) {
        this.memoryUseCase = memoryUseCase;
    }

    @PostMapping
    @Operation(
            summary = "Store a new episodic memory",
            description = "Creates a new EPISODIC memory, generates its vector embedding, and persists it"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Memory created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @ApiResponse(responseCode = "503", description = "Embedding service unavailable")
    })
    public ResponseEntity<MemoryResponse> store(
            @Parameter(description = "Tenant identifier for data isolation", required = true, example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody MemoryCreateRequest request) {

        MemoryResponse response = memoryUseCase.store(request, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/search")
    @Operation(
            summary = "Search memories by semantic similarity",
            description = "Performs hybrid search combining cosine similarity and recency decay (ADR-002)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results returned"),
            @ApiResponse(responseCode = "400", description = "Invalid query or limit parameter"),
            @ApiResponse(responseCode = "503", description = "Embedding service unavailable")
    })
    public ResponseEntity<MemorySearchResponse> search(
            @Parameter(description = "Tenant identifier for data isolation", required = true, example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Parameter(description = "Natural language search query", required = true, example = "user preferences for IDE theme")
            @RequestParam String query,
            @Parameter(description = "Maximum number of results to return", example = "10")
            @RequestParam(defaultValue = "10") int limit) {

        if (query.isBlank()) {
            throw new InvalidRequestException("Search query must not be blank");
        }
        if (limit <= 0) {
            throw new InvalidRequestException("limit must be a positive integer");
        }

        MemorySearchResponse response = memoryUseCase.search(tenantId, query, limit);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Retrieve a memory by ID",
            description = "Fetches a specific memory and refreshes its last_accessed_at timestamp (ADR-003: memory decay)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Memory found and returned"),
            @ApiResponse(responseCode = "404", description = "Memory not found or belongs to another tenant")
    })
    public ResponseEntity<MemoryResponse> findById(
            @Parameter(description = "Tenant identifier for data isolation", required = true, example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Parameter(description = "Memory UUID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID id) {

        MemoryResponse response = memoryUseCase.findById(id, tenantId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a memory by ID",
            description = "Soft deletes a specific memory. It will no longer appear in searches or be consolidated."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Memory deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Memory not found, already deleted, or belongs to another tenant")
    })
    public ResponseEntity<Void> deleteMemory(
            @Parameter(description = "Tenant identifier for data isolation", required = true)
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Parameter(description = "Memory UUID", required = true)
            @PathVariable UUID id) {

        memoryUseCase.deleteMemory(id, tenantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    @Operation(
            summary = "Get memory statistics",
            description = "Retrieves business metrics about the tenant's memories and the status of the global consolidation job."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    })
    public ResponseEntity<MemoryStatsResponse> getStats(
            @Parameter(description = "Tenant identifier for data isolation", required = true)
            @RequestHeader("X-Tenant-Id") UUID tenantId) {

        MemoryStatsResponse stats = memoryUseCase.getMemoryStats(tenantId);
        return ResponseEntity.ok(stats);
    }
}
