package com.agentmemorystore.domain.exception;

/**
 * Thrown when the embedding service (e.g., OpenAI) is unavailable or returns an error.
 * This exception intentionally hides provider-specific details and API keys
 * from the consumer-facing API response.
 */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
