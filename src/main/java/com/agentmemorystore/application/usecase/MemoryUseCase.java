package com.agentmemorystore.application.usecase;

import com.agentmemorystore.application.dto.MemoryCreateRequest;
import com.agentmemorystore.application.dto.MemoryResponse;
import com.agentmemorystore.application.dto.MemorySearchResponse;
import com.agentmemorystore.domain.exception.MemoryNotFoundException;
import com.agentmemorystore.domain.model.Memory;
import com.agentmemorystore.domain.model.MemoryType;
import com.agentmemorystore.domain.port.out.EmbeddingPort;
import com.agentmemorystore.domain.port.out.MemoryRepository;
import com.agentmemorystore.application.dto.MemoryStatsResponse;
import com.agentmemorystore.domain.model.MemoryStats;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Application service orchestrating memory operations.
 * Coordinates between domain ports without containing business rules itself.
 */
@Service
public class MemoryUseCase {

    private final MemoryRepository memoryRepository;
    private final EmbeddingPort embeddingPort;
    private final JobExplorer jobExplorer;
    private final double semanticWeight;
    private final double recencyWeight;

    public MemoryUseCase(MemoryRepository memoryRepository,
                         EmbeddingPort embeddingPort,
                         JobExplorer jobExplorer,
                         @Value("${memory.search.semantic-weight:0.7}") double semanticWeight,
                         @Value("${memory.search.recency-weight:0.3}") double recencyWeight) {
        this.memoryRepository = memoryRepository;
        this.embeddingPort = embeddingPort;
        this.jobExplorer = jobExplorer;
        this.semanticWeight = semanticWeight;
        this.recencyWeight = recencyWeight;
    }

    /**
     * Stores a new EPISODIC memory for the given tenant.
     * Generates an embedding from the content before persisting.
     */
    public MemoryResponse store(MemoryCreateRequest request, UUID tenantId) {
        float[] embedding = embeddingPort.generateEmbedding(request.content());

        Memory memory = new Memory();
        memory.setTenantId(tenantId);
        memory.setContent(request.content());
        memory.setEmbedding(embedding);
        memory.setMemoryType(MemoryType.EPISODIC);
        memory.setSourceMemoryIds(Collections.emptyList());
        memory.setLastAccessedAt(Instant.now());
        memory.setCreatedAt(Instant.now());

        Memory saved = memoryRepository.save(memory);
        return toResponse(saved);
    }

    /**
     * Searches for memories matching the query using hybrid search (semantic + recency).
     */
    public MemorySearchResponse search(UUID tenantId, String query, int limit) {
        float[] queryEmbedding = embeddingPort.generateEmbedding(query);

        List<Memory> results = memoryRepository.searchByEmbedding(
                tenantId, queryEmbedding, limit, semanticWeight, recencyWeight
        );

        List<MemoryResponse> responses = results.stream()
                .map(this::toResponse)
                .toList();

        return new MemorySearchResponse(responses, responses.size(), semanticWeight);
    }

    /**
     * Finds a memory by ID and updates its last accessed timestamp (ADR-003: memory decay).
     */
    public MemoryResponse findById(UUID id, UUID tenantId) {
        Memory memory = memoryRepository.findById(id, tenantId)
                .orElseThrow(() -> new MemoryNotFoundException(id));

        Instant now = Instant.now();
        memoryRepository.updateLastAccessedAt(id, now);
        memory.setLastAccessedAt(now);

        return toResponse(memory);
    }

    /**
     * Soft deletes a memory by ID for the given tenant.
     * Throws {@link MemoryNotFoundException} (mapped to 404) when nothing was deleted —
     * the memory does not exist, belongs to another tenant, or was already deleted.
     */
    public void deleteMemory(UUID id, UUID tenantId) {
        int affected = memoryRepository.delete(id, tenantId);
        if (affected == 0) {
            throw new MemoryNotFoundException(id);
        }
    }

    /**
     * Retrieves memory statistics for the given tenant, including global job status.
     */
    public MemoryStatsResponse getMemoryStats(UUID tenantId) {
        MemoryStats stats = memoryRepository.getStats(tenantId);

        Instant lastConsolidationAt = null;
        String lastConsolidationStatus = "NEVER_RUN";
        long memoriesConsolidatedInLastRun = 0L;

        try {
            List<JobInstance> jobInstances = jobExplorer.findJobInstancesByJobName("consolidationJob", 0, 1);
            if (!jobInstances.isEmpty()) {
                JobExecution lastExecution = jobExplorer.getLastJobExecution(jobInstances.get(0));
                if (lastExecution != null) {
                    java.time.LocalDateTime endTime = lastExecution.getEndTime();
                    java.time.LocalDateTime startTime = lastExecution.getStartTime();
                    
                    if (endTime != null) {
                        lastConsolidationAt = endTime.toInstant(java.time.ZoneOffset.UTC);
                    } else if (startTime != null) {
                        lastConsolidationAt = startTime.toInstant(java.time.ZoneOffset.UTC);
                    }
                    lastConsolidationStatus = lastExecution.getStatus().name();
                    
                    // We can aggregate the write count from step executions as an indicator
                    memoriesConsolidatedInLastRun = lastExecution.getStepExecutions().stream()
                            .mapToLong(org.springframework.batch.core.StepExecution::getWriteCount)
                            .sum();
                }
            }
        } catch (Exception e) {
            // Log but don't fail the stats endpoint if Spring Batch explorer throws
            lastConsolidationStatus = "UNKNOWN";
        }

        return new MemoryStatsResponse(
                stats.totalMemories(),
                stats.consolidatedEpisodic(),
                stats.byType(),
                lastConsolidationAt,
                lastConsolidationStatus,
                memoriesConsolidatedInLastRun
        );
    }

    private MemoryResponse toResponse(Memory memory) {
        return new MemoryResponse(
                memory.getId(),
                memory.getTenantId(),
                memory.getContent(),
                memory.getMemoryType().name(),
                memory.getSourceMemoryIds(),
                memory.getLastAccessedAt(),
                memory.getCreatedAt(),
                memory.getRelevanceScore()
        );
    }
}
