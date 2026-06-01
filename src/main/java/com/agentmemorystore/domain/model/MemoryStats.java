package com.agentmemorystore.domain.model;

import java.util.Map;

/**
 * Domain record holding statistics for a tenant's memories.
 */
public record MemoryStats(
        long totalMemories,
        long consolidatedEpisodic,
        Map<String, Long> byType
) {
}
