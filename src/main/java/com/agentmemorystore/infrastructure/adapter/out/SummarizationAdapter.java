package com.agentmemorystore.infrastructure.adapter.out;

import com.agentmemorystore.domain.exception.EmbeddingUnavailableException;
import com.agentmemorystore.domain.port.out.SummarizationPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Infrastructure adapter implementing {@link SummarizationPort} using Spring AI's {@link ChatModel}.
 * Protected by Resilience4j RateLimiter and CircuitBreaker.
 */
@Component
public class SummarizationAdapter implements SummarizationPort {

    private static final Logger log = LoggerFactory.getLogger(SummarizationAdapter.class);

    private final ChatModel chatModel;

    public SummarizationAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    @RateLimiter(name = "openai-embedding", fallbackMethod = "rateLimitFallback")
    @CircuitBreaker(name = "embeddingService", fallbackMethod = "summarizationFallback")
    public String summarize(List<String> memories, String promptInstruction) {
        String combinedMemories = String.join("\n- ", memories);
        String fullPromptText = promptInstruction + "\n\nMemories:\n- " + combinedMemories;

        Prompt prompt = new Prompt(fullPromptText);
        return chatModel.call(prompt).getResult().getOutput().getContent();
    }

    @SuppressWarnings("unused")
    private String rateLimitFallback(List<String> memories, String promptInstruction, Throwable t) {
        log.warn("Rate limit exceeded for summarization service.", t);
        // Throw the same exception so the Batch Step can handle it via skip/retry
        throw new EmbeddingUnavailableException("Rate limit exceeded for summarization service", t);
    }

    @SuppressWarnings("unused")
    private String summarizationFallback(List<String> memories, String promptInstruction, Throwable t) {
        log.error("Summarization service unavailable. Circuit breaker fallback triggered.", t);
        throw new EmbeddingUnavailableException("The summarization service is temporarily unavailable.", t);
    }
}
