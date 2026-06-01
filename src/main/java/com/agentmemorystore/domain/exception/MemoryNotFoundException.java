package com.agentmemorystore.domain.exception;

import java.util.UUID;

/**
 * Thrown when a memory with the given ID is not found in the repository.
 */
public class MemoryNotFoundException extends RuntimeException {

    public MemoryNotFoundException(UUID id) {
        super("Memory not found with id: " + id);
    }
}
