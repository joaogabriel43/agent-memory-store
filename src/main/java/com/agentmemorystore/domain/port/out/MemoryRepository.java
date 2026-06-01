package com.agentmemorystore.domain.port.out;

import com.agentmemorystore.domain.model.Memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for memory persistence operations.
 * Implemented by infrastructure adapters (e.g., JDBC/pgvector).
 */
public interface MemoryRepository {

    Memory save(Memory memory);

    Optional<Memory> findById(UUID id);

    List<Memory> searchByEmbedding(UUID tenantId, float[] embedding, int limit,
                                   double semanticWeight, double recencyWeight);

    List<Memory> findEpisodicForConsolidation(UUID tenantId, int minCount, int ageDays);

    void updateLastAccessedAt(UUID id, Instant accessedAt);
}
