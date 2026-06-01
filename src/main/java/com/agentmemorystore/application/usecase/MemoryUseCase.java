package com.agentmemorystore.application.usecase;

import com.agentmemorystore.application.dto.MemoryCreateRequest;
import com.agentmemorystore.application.dto.MemoryResponse;
import com.agentmemorystore.application.dto.MemorySearchResponse;
import com.agentmemorystore.domain.exception.MemoryNotFoundException;
import com.agentmemorystore.domain.model.Memory;
import com.agentmemorystore.domain.model.MemoryType;
import com.agentmemorystore.domain.port.out.EmbeddingPort;
import com.agentmemorystore.domain.port.out.MemoryRepository;
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
    private final double semanticWeight;
    private final double recencyWeight;

    public MemoryUseCase(MemoryRepository memoryRepository,
                         EmbeddingPort embeddingPort,
                         @Value("${memory.search.semantic-weight:0.7}") double semanticWeight,
                         @Value("${memory.search.recency-weight:0.3}") double recencyWeight) {
        this.memoryRepository = memoryRepository;
        this.embeddingPort = embeddingPort;
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
        Memory memory = memoryRepository.findById(id)
                .filter(m -> m.getTenantId().equals(tenantId))
                .orElseThrow(() -> new MemoryNotFoundException(id));

        Instant now = Instant.now();
        memoryRepository.updateLastAccessedAt(id, now);
        memory.setLastAccessedAt(now);

        return toResponse(memory);
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
