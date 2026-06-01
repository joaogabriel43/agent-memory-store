package com.agentmemorystore.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Response payload for memory statistics")
public record MemoryStatsResponse(

        @Schema(description = "Total number of active memories for the tenant", example = "150")
        long totalMemories,

        @Schema(description = "Number of episodic memories that have been consolidated", example = "42")
        long consolidatedEpisodic,

        @Schema(description = "Breakdown of active memories by type", example = "{\"EPISODIC\": 100, \"SEMANTIC\": 50}")
        Map<String, Long> byType,

        @Schema(description = "Timestamp of the last successful consolidation job execution", example = "2023-10-27T10:00:00Z")
        Instant lastConsolidationAt,

        @Schema(description = "Status of the last consolidation job execution", example = "COMPLETED")
        String lastConsolidationStatus,

        @Schema(description = "Number of memories successfully processed in the last consolidation run", example = "15")
        long memoriesConsolidatedInLastRun
) {
}
