package com.agentmemorystore.domain.port.out;

import java.util.List;

/**
 * Output port for summarizing episodic memories into semantic ones.
 * Pure Java interface — keeps the domain isolated from LLM framework implementations.
 */
public interface SummarizationPort {

    /**
     * Generates a concise semantic summary from a list of episodic memory contents.
     *
     * @param memories The text contents of the memories to summarize.
     * @param prompt   The system instruction prompt for the LLM.
     * @return The summarized text.
     */
    String summarize(List<String> memories, String prompt);
}
