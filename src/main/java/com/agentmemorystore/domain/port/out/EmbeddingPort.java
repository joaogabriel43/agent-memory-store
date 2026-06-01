package com.agentmemorystore.domain.port.out;

/**
 * Output port for generating vector embeddings from text content.
 * Implemented by infrastructure adapters (e.g., Spring AI / OpenAI).
 * <p>
 * This interface uses only pure Java types ({@code float[]}) to ensure
 * the domain layer remains free of any AI framework dependencies.
 */
public interface EmbeddingPort {

    float[] generateEmbedding(String content);
}
