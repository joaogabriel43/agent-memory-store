package com.agentmemorystore.infrastructure.adapter.out;

import com.agentmemorystore.domain.exception.EmbeddingUnavailableException;
import com.agentmemorystore.domain.port.out.EmbeddingPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Infrastructure adapter implementing {@link EmbeddingPort} via Spring AI's {@link EmbeddingModel}.
 * Protected by a Resilience4j circuit breaker to handle OpenAI failures gracefully.
 * <p>
 * ADR-004: Always uses the {@code EmbeddingModel} abstraction — never calls OpenAI directly.
 */
@Component
public class EmbeddingAdapter implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingAdapter.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    @CircuitBreaker(name = "embeddingService", fallbackMethod = "embeddingFallback")
    public float[] generateEmbedding(String content) {
        List<Double> embedding = embeddingModel.embed(content);
        return toFloatArray(embedding);
    }

    /**
     * Fallback triggered when the embedding service is unavailable.
     * Throws a domain exception that hides provider details from the API consumer.
     */
    @SuppressWarnings("unused")
    private float[] embeddingFallback(String content, Throwable throwable) {
        log.error("Embedding service unavailable. Circuit breaker fallback triggered.", throwable);
        throw new EmbeddingUnavailableException(
                "The embedding service is temporarily unavailable. Please try again later."
        );
    }

    private float[] toFloatArray(List<Double> doubles) {
        float[] result = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            result[i] = doubles.get(i).floatValue();
        }
        return result;
    }
}
