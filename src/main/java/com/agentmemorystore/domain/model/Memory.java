package com.agentmemorystore.domain.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Core domain entity representing a memory stored for an AI agent.
 * This class has zero framework dependencies — pure Java only.
 */
public class Memory {

    private UUID id;
    private UUID tenantId;
    private String content;
    private float[] embedding;
    private MemoryType memoryType;
    private List<UUID> sourceMemoryIds;
    private Instant lastAccessedAt;
    private Instant createdAt;
    private boolean consolidated;

    /** Transient field populated only during search results. */
    private Double relevanceScore;

    public Memory() {
    }

    public Memory(UUID id, UUID tenantId, String content, float[] embedding,
                  MemoryType memoryType, List<UUID> sourceMemoryIds,
                  Instant lastAccessedAt, Instant createdAt, boolean consolidated, Double relevanceScore) {
        this.id = id;
        this.tenantId = tenantId;
        this.content = content;
        this.embedding = embedding != null ? Arrays.copyOf(embedding, embedding.length) : null;
        this.memoryType = memoryType;
        this.sourceMemoryIds = sourceMemoryIds != null
                ? Collections.unmodifiableList(sourceMemoryIds)
                : Collections.emptyList();
        this.lastAccessedAt = lastAccessedAt;
        this.createdAt = createdAt;
        this.consolidated = consolidated;
        this.relevanceScore = relevanceScore;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getEmbedding() {
        return embedding != null ? Arrays.copyOf(embedding, embedding.length) : null;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding != null ? Arrays.copyOf(embedding, embedding.length) : null;
    }

    public MemoryType getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(MemoryType memoryType) {
        this.memoryType = memoryType;
    }

    public List<UUID> getSourceMemoryIds() {
        return sourceMemoryIds != null
                ? Collections.unmodifiableList(sourceMemoryIds)
                : Collections.emptyList();
    }

    public void setSourceMemoryIds(List<UUID> sourceMemoryIds) {
        this.sourceMemoryIds = sourceMemoryIds != null
                ? List.copyOf(sourceMemoryIds)
                : Collections.emptyList();
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isConsolidated() {
        return consolidated;
    }

    public void setConsolidated(boolean consolidated) {
        this.consolidated = consolidated;
    }

    public Double getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(Double relevanceScore) {
        this.relevanceScore = relevanceScore;
    }
}
