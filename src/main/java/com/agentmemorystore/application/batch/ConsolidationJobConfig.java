package com.agentmemorystore.application.batch;

import com.agentmemorystore.domain.exception.EmbeddingUnavailableException;
import com.agentmemorystore.domain.model.Memory;
import com.agentmemorystore.domain.model.MemoryType;
import com.agentmemorystore.domain.port.out.EmbeddingPort;
import com.agentmemorystore.domain.port.out.MemoryRepository;
import com.agentmemorystore.domain.port.out.SummarizationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring Batch configuration for the memory consolidation job.
 * Processes old episodic memories and summarizes them into semantic memories.
 */
@Configuration
public class ConsolidationJobConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationJobConfig.class);

    private final MemoryRepository memoryRepository;
    private final SummarizationPort summarizationPort;
    private final EmbeddingPort embeddingPort;

    public ConsolidationJobConfig(MemoryRepository memoryRepository,
                                  SummarizationPort summarizationPort,
                                  EmbeddingPort embeddingPort) {
        this.memoryRepository = memoryRepository;
        this.summarizationPort = summarizationPort;
        this.embeddingPort = embeddingPort;
    }

    /**
     * Custom JobLauncher to execute jobs asynchronously so the REST API doesn't block.
     * Marked {@code @Primary} so it wins over Spring Boot's auto-configured synchronous
     * {@code jobLauncher}; without this the controller would silently bind to the blocking one.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public TaskExecutorJobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        jobLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor("batch-"));
        jobLauncher.afterPropertiesSet();
        return jobLauncher;
    }

    @Bean
    public Job consolidationJob(JobRepository jobRepository, Step consolidationStep) {
        return new JobBuilder("consolidationJob", jobRepository)
                .start(consolidationStep)
                .build();
    }

    @Bean
    public Step consolidationStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  ItemReader<UUID> tenantReader,
                                  ItemProcessor<UUID, Memory> tenantProcessor,
                                  ItemWriter<Memory> semanticMemoryWriter) {
        return new StepBuilder("consolidationStep", jobRepository)
                .<UUID, Memory>chunk(5, transactionManager)
                .reader(tenantReader)
                .processor(tenantProcessor)
                .writer(semanticMemoryWriter)
                .faultTolerant()
                // If the LLM rate limits or fails, retry up to 3 times per item
                .retry(EmbeddingUnavailableException.class)
                .retryLimit(3)
                // If it continues to fail, skip this tenant and move to the next (up to 10 skips)
                .skip(EmbeddingUnavailableException.class)
                .skipLimit(10)
                .build();
    }

    @Bean
    @StepScope
    public ItemReader<UUID> tenantReader(
            @Value("${memory.consolidation.min-episodic-count:5}") int minCount,
            @Value("${memory.consolidation.age-days:7}") int ageDays) {
        
        log.info("Reading tenants ready for consolidation (minCount={}, ageDays={})", minCount, ageDays);
        List<UUID> tenants = memoryRepository.findTenantsReadyForConsolidation(minCount, ageDays);
        return new ListItemReader<>(tenants);
    }

    @Bean
    @StepScope
    public ItemProcessor<UUID, Memory> tenantProcessor(
            @Value("${memory.consolidation.min-episodic-count:5}") int minCount,
            @Value("${memory.consolidation.age-days:7}") int ageDays,
            @Value("${memory.consolidation.prompt}") String prompt) {

        return tenantId -> {
            log.info("Processing tenant {}", tenantId);
            List<Memory> episodicMemories = memoryRepository.findEpisodicForConsolidation(tenantId, minCount, ageDays);
            
            if (episodicMemories.isEmpty()) {
                return null; // Skip if no memories found during processing
            }

            List<String> contents = episodicMemories.stream()
                    .map(Memory::getContent)
                    .toList();

            // 1. Call LLM to summarize
            String summary = summarizationPort.summarize(contents, prompt);

            // 2. Generate embedding for the new summary
            float[] embedding = embeddingPort.generateEmbedding(summary);

            // 3. Create the new SEMANTIC memory
            List<UUID> sourceIds = episodicMemories.stream()
                    .map(Memory::getId)
                    .toList();

            Memory semanticMemory = new Memory();
            semanticMemory.setTenantId(tenantId);
            semanticMemory.setContent(summary);
            semanticMemory.setEmbedding(embedding);
            semanticMemory.setMemoryType(MemoryType.SEMANTIC);
            semanticMemory.setSourceMemoryIds(sourceIds);
            semanticMemory.setCreatedAt(Instant.now());
            semanticMemory.setLastAccessedAt(Instant.now());
            semanticMemory.setConsolidated(false); // The semantic memory itself isn't consolidated

            return semanticMemory;
        };
    }

    @Bean
    public ItemWriter<Memory> semanticMemoryWriter() {
        return items -> {
            for (Memory semanticMemory : items) {
                // Save the new semantic memory
                memoryRepository.save(semanticMemory);
                
                // Mark the source episodic memories as consolidated
                memoryRepository.markAsConsolidated(semanticMemory.getSourceMemoryIds());
                
                log.info("Consolidated {} memories for tenant {}", 
                        semanticMemory.getSourceMemoryIds().size(), semanticMemory.getTenantId());
            }
        };
    }
}
