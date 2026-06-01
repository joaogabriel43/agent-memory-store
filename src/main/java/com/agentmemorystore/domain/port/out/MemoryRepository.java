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

    Optional<Memory> findById(UUID id, UUID tenantId);

    List<Memory> searchByEmbedding(UUID tenantId, float[] embedding, int limit,
                                   double semanticWeight, double recencyWeight);

    List<Memory> findEpisodicForConsolidation(UUID tenantId, int minCount, int ageDays);

    void updateLastAccessedAt(UUID id, Instant accessedAt);

    List<UUID> findTenantsReadyForConsolidation(int minCount, int ageDays);

    void markAsConsolidated(List<UUID> memoryIds);

    /**
     * Soft deletes a memory for the given tenant.
     *
     * @return the number of rows affected (0 if the memory does not exist,
     *         belongs to another tenant, or was already deleted).
     */
    int delete(UUID id, UUID tenantId);

    com.agentmemorystore.domain.model.MemoryStats getStats(UUID tenantId);
}
