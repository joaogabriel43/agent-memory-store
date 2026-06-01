package com.agentmemorystore.infrastructure.adapter.out;

import com.agentmemorystore.domain.model.MemoryStats;
import com.agentmemorystore.domain.model.Memory;
import com.agentmemorystore.domain.model.MemoryType;
import com.agentmemorystore.domain.port.out.MemoryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-based implementation of {@link MemoryRepository} using pgvector for semantic search.
 * Uses Spring's {@link JdbcClient} for clean, type-safe query execution.
 * <p>
 * The hybrid search query combines cosine similarity with exponential time decay,
 * weighted by configurable parameters (ADR-002).
 */
@Repository
public class MemoryRepositoryImpl implements MemoryRepository {

    private final JdbcClient jdbcClient;

    public MemoryRepositoryImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Memory save(Memory memory) {
        UUID id = UUID.randomUUID();
        String embeddingVector = floatArrayToVectorString(memory.getEmbedding());
        UUID[] sourceIds = memory.getSourceMemoryIds() != null
                ? memory.getSourceMemoryIds().toArray(UUID[]::new)
                : new UUID[0];

        jdbcClient.sql("""
                INSERT INTO memories (id, tenant_id, content, embedding, memory_type,
                                      source_memory_ids, last_accessed_at, created_at, consolidated)
                VALUES (:id, :tenantId, :content, :embedding::vector, :memoryType,
                        :sourceMemoryIds, :lastAccessedAt, :createdAt, :consolidated)
                """)
                .param("id", id)
                .param("tenantId", memory.getTenantId().toString())
                .param("content", memory.getContent())
                .param("embedding", embeddingVector)
                .param("memoryType", memory.getMemoryType().name())
                .param("sourceMemoryIds", sourceIds)
                .param("lastAccessedAt", Timestamp.from(memory.getLastAccessedAt()))
                .param("createdAt", Timestamp.from(memory.getCreatedAt()))
                .param("consolidated", memory.isConsolidated())
                .update();

        memory.setId(id);
        return memory;
    }

    @Override
    public Optional<Memory> findById(UUID id, UUID tenantId) {
        return jdbcClient.sql("""
                SELECT id, tenant_id, content, embedding, memory_type,
                       source_memory_ids, last_accessed_at, created_at, consolidated
                FROM memories
                WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL
                """)
                .param("id", id)
                .param("tenantId", tenantId.toString())
                .query((rs, rowNum) -> mapRow(rs, null))
                .optional();
    }

    @Override
    public List<Memory> searchByEmbedding(UUID tenantId, float[] embedding, int limit,
                                          double semanticWeight, double recencyWeight) {
        String queryVector = floatArrayToVectorString(embedding);

        return jdbcClient.sql("""
                SELECT id, tenant_id, content, embedding, memory_type,
                       source_memory_ids, last_accessed_at, created_at, consolidated,
                       (:semanticWeight * (1 - (embedding <=> :queryVector::vector)) +
                        :recencyWeight * EXP(-0.1 * EXTRACT(EPOCH FROM (NOW() - last_accessed_at)) / 86400))
                       AS relevance_score
                FROM memories
                WHERE tenant_id = :tenantId AND embedding IS NOT NULL AND deleted_at IS NULL
                ORDER BY relevance_score DESC
                LIMIT :limit
                """)
                .param("semanticWeight", semanticWeight)
                .param("recencyWeight", recencyWeight)
                .param("queryVector", queryVector)
                .param("tenantId", tenantId.toString())
                .param("limit", limit)
                .query((rs, rowNum) -> mapRow(rs, rs.getDouble("relevance_score")))
                .list();
    }

    @Override
    public List<Memory> findEpisodicForConsolidation(UUID tenantId, int minCount, int ageDays) {
        return jdbcClient.sql("""
                SELECT id, tenant_id, content, embedding, memory_type,
                       source_memory_ids, last_accessed_at, created_at, consolidated
                FROM memories
                WHERE tenant_id = :tenantId
                  AND memory_type = 'EPISODIC'
                  AND consolidated = FALSE
                  AND deleted_at IS NULL
                  AND created_at < NOW() - CAST(:ageDays || ' days' AS INTERVAL)
                ORDER BY created_at ASC
                """)
                .param("tenantId", tenantId.toString())
                .param("ageDays", ageDays)
                .query((rs, rowNum) -> mapRow(rs, null))
                .list();
    }

    @Override
    public void updateLastAccessedAt(UUID id, Instant accessedAt) {
        jdbcClient.sql("UPDATE memories SET last_accessed_at = :accessedAt WHERE id = :id")
                .param("accessedAt", Timestamp.from(accessedAt))
                .param("id", id)
                .update();
    }

    @Override
    public List<UUID> findTenantsReadyForConsolidation(int minCount, int ageDays) {
        return jdbcClient.sql("""
                SELECT tenant_id
                FROM memories
                WHERE memory_type = 'EPISODIC'
                  AND consolidated = FALSE
                  AND deleted_at IS NULL
                  AND created_at < NOW() - CAST(:ageDays || ' days' AS INTERVAL)
                GROUP BY tenant_id
                HAVING COUNT(*) >= :minCount
                ORDER BY tenant_id
                """)
                .param("ageDays", ageDays)
                .param("minCount", minCount)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("tenant_id")))
                .list();
    }

    @Override
    public void markAsConsolidated(List<UUID> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        UUID[] idsArray = memoryIds.toArray(new UUID[0]);
        jdbcClient.sql("UPDATE memories SET consolidated = TRUE WHERE id = ANY(:ids)")
                .param("ids", idsArray)
                .update();
    }

    @Override
    public int delete(UUID id, UUID tenantId) {
        // `deleted_at IS NULL` guarantees a second delete affects 0 rows (idempotent 404),
        // and the tenant filter ensures cross-tenant deletes are invisible.
        return jdbcClient.sql("""
                UPDATE memories SET deleted_at = NOW()
                WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL
                """)
                .param("id", id)
                .param("tenantId", tenantId.toString())
                .update();
    }

    @Override
    public MemoryStats getStats(UUID tenantId) {
        // Query to get overall counts and counts by type
        Map<String, Long> byType = new HashMap<>();
        
        jdbcClient.sql("""
                SELECT memory_type, COUNT(*) as cnt
                FROM memories
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                GROUP BY memory_type
                """)
                .param("tenantId", tenantId.toString())
                .query((rs, rowNum) -> {
                    byType.put(rs.getString("memory_type"), rs.getLong("cnt"));
                    return null;
                })
                .list();

        long totalMemories = byType.values().stream().mapToLong(Long::longValue).sum();

        // Query to get count of consolidated episodic memories
        long consolidatedEpisodic = jdbcClient.sql("""
                SELECT COUNT(*) as cnt
                FROM memories
                WHERE tenant_id = :tenantId AND memory_type = 'EPISODIC' AND consolidated = TRUE AND deleted_at IS NULL
                """)
                .param("tenantId", tenantId.toString())
                .query(rs -> rs.next() ? rs.getLong("cnt") : 0L);

        return new MemoryStats(totalMemories, consolidatedEpisodic, byType);
    }

    /**
     * Maps a JDBC ResultSet row to a domain Memory entity.
     */
    private Memory mapRow(ResultSet rs, Double relevanceScore) throws SQLException {
        UUID[] sourceIds = extractSourceMemoryIds(rs);

        Memory memory = new Memory();
        memory.setId(rs.getObject("id", UUID.class));
        memory.setTenantId(UUID.fromString(rs.getString("tenant_id")));
        memory.setContent(rs.getString("content"));
        memory.setMemoryType(MemoryType.valueOf(rs.getString("memory_type")));
        memory.setSourceMemoryIds(sourceIds != null ? Arrays.asList(sourceIds) : Collections.emptyList());
        memory.setLastAccessedAt(rs.getTimestamp("last_accessed_at").toInstant());
        memory.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        memory.setConsolidated(rs.getBoolean("consolidated"));
        memory.setRelevanceScore(relevanceScore);
        return memory;
    }

    private UUID[] extractSourceMemoryIds(ResultSet rs) throws SQLException {
        Array sqlArray = rs.getArray("source_memory_ids");
        if (sqlArray == null) {
            return new UUID[0];
        }
        return (UUID[]) sqlArray.getArray();
    }

    /**
     * Converts a float[] to the pgvector string format: "[0.1,0.2,0.3]".
     * Required because JdbcClient cannot natively bind float[] to the vector type.
     */
    private String floatArrayToVectorString(float[] embedding) {
        if (embedding == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
