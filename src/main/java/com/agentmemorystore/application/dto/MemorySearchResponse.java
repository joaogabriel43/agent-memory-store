package com.agentmemorystore.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Wrapper response for memory search operations.
 */
@Schema(description = "Response wrapper for memory search results")
public record MemorySearchResponse(

        @Schema(description = "List of memories matching the search query")
        List<MemoryResponse> memories,

        @Schema(description = "Total number of memories found", example = "5")
        long totalFound,

        @Schema(description = "Semantic weight used in this search", example = "0.7")
        double semanticWeightUsed
) {
}
