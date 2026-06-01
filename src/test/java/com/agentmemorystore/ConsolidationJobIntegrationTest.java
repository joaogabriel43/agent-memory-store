package com.agentmemorystore;

import com.agentmemorystore.application.dto.JobStatusResponse;
import com.agentmemorystore.domain.model.Memory;
import com.agentmemorystore.domain.model.MemoryType;
import com.agentmemorystore.domain.port.out.MemoryRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration tests for the Consolidation Job.
 * Uses JobLauncherTestUtils and Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SpringBatchTest
@ActiveProfiles("test")
@Testcontainers
class ConsolidationJobIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("aaaa1111-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbb2222-0000-0000-0000-000000000002");
    private static final UUID TENANT_C = UUID.fromString("cccc3333-0000-0000-0000-000000000003");

    private static WireMockServer wireMockServer;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("agent_memory_store_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private MemoryRepository memoryRepository;

    @Autowired
    private Job consolidationJob;

    @Autowired
    private JobRepository jobRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.ai.openai.base-url", () -> wireMockServer.baseUrl());
        registry.add("wiremock.server.port", () -> wireMockServer.port());
    }

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() throws Exception {
        wireMockServer.resetAll();
        jobLauncherTestUtils.setJob(consolidationJob);
        // The application's primary JobLauncher is asynchronous (for the REST API). Tests need a
        // synchronous launcher so launchJob() returns a finished execution to assert against.
        TaskExecutorJobLauncher syncLauncher = new TaskExecutorJobLauncher();
        syncLauncher.setJobRepository(jobRepository);
        syncLauncher.setTaskExecutor(new SyncTaskExecutor());
        syncLauncher.afterPropertiesSet();
        jobLauncherTestUtils.setJobLauncher(syncLauncher);
    }

    @Test
    @DisplayName("Job runs successfully, creates SEMANTIC memories with source IDs, and marks originals as consolidated")
    void testConsolidationSuccessAndIdempotency() throws Exception {
        stubOpenAiSuccess();

        // Seed un-consolidated memories for Tenant A
        seedEpisodicMemories(TENANT_A, 3, 2); // 3 memories, 2 days old

        // Run job
        JobExecution execution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        // Verify SEMANTIC memory was created
        List<Memory> semanticMemories = memoryRepository.searchByEmbedding(TENANT_A, new float[1536], 10, 0, 0)
                .stream()
                .filter(m -> m.getMemoryType() == MemoryType.SEMANTIC)
                .toList();

        assertThat(semanticMemories).hasSize(1);
        Memory semantic = semanticMemories.get(0);
        assertThat(semantic.getSourceMemoryIds()).hasSize(3);
        assertThat(semantic.isConsolidated()).isFalse();

        // Verify original episodic memories are now consolidated = true
        List<Memory> episodic = memoryRepository.searchByEmbedding(TENANT_A, new float[1536], 10, 0, 0)
                .stream()
                .filter(m -> m.getMemoryType() == MemoryType.EPISODIC)
                .toList();

        assertThat(episodic).allMatch(Memory::isConsolidated);

        // ==== Test Idempotency ====
        // Running it again should result in no new semantic memories
        JobExecution secondExecution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());

        assertThat(secondExecution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        List<Memory> semanticMemoriesAfter = memoryRepository.searchByEmbedding(TENANT_A, new float[1536], 10, 0, 0)
                .stream()
                .filter(m -> m.getMemoryType() == MemoryType.SEMANTIC)
                .toList();

        assertThat(semanticMemoriesAfter).hasSize(1); // Still 1, didn't create a new one
    }

    @Test
    @DisplayName("Job skips tenant on LLM failure and processes next tenant successfully")
    void testFaultToleranceAndSkip() throws Exception {
        // Tenant B will fail, Tenant C will succeed. WireMock scenarios help mock stateful responses,
        // but here we just mock embeddings to succeed and chat to fail initially, then succeed.
        // Spring AI ChatModel calls /v1/chat/completions.
        
        // Mock embeddings always success
        stubOpenAiEmbeddingSuccess();

        // Mock chat to return 500 for the first few calls (causing a skip for the first tenant),
        // then return 200 for subsequent calls.
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("FaultTolerance")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("FirstFailed"));

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("FaultTolerance")
                .whenScenarioStateIs("FirstFailed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("SecondFailed"));
                
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("FaultTolerance")
                .whenScenarioStateIs("SecondFailed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("ThirdFailed"));

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("FaultTolerance")
                .whenScenarioStateIs("ThirdFailed")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(generateChatResponseJson())));

        seedEpisodicMemories(TENANT_B, 2, 2);
        seedEpisodicMemories(TENANT_C, 2, 2);

        JobExecution execution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());

        // The job should complete successfully despite the skips
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        // Tenant B should have 0 semantic memories (was skipped)
        List<Memory> semanticB = memoryRepository.searchByEmbedding(TENANT_B, new float[1536], 10, 0, 0)
                .stream().filter(m -> m.getMemoryType() == MemoryType.SEMANTIC).toList();
        assertThat(semanticB).isEmpty();

        // Tenant C should have 1 semantic memory (processed successfully after B failed)
        List<Memory> semanticC = memoryRepository.searchByEmbedding(TENANT_C, new float[1536], 10, 0, 0)
                .stream().filter(m -> m.getMemoryType() == MemoryType.SEMANTIC).toList();
        assertThat(semanticC).hasSize(1);
    }

    @Test
    @DisplayName("API triggers job asynchronously and returns 202 Accepted with status URL")
    void testApiTriggerAndPolling() {
        ResponseEntity<JobStatusResponse> postResponse = restTemplate.postForEntity(
                "/api/v1/jobs/consolidation", null, JobStatusResponse.class
        );

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(postResponse.getBody()).isNotNull();
        assertThat(postResponse.getBody().jobExecutionId()).isGreaterThan(0);
        assertThat(postResponse.getBody().statusUrl()).isNotBlank();

        long executionId = postResponse.getBody().jobExecutionId();

        // Poll until completion (or fail after 5s)
        await().atMost(5, SECONDS).untilAsserted(() -> {
            ResponseEntity<JobStatusResponse> getResponse = restTemplate.getForEntity(
                    "/api/v1/jobs/consolidation/" + executionId + "/status",
                    JobStatusResponse.class
            );
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(getResponse.getBody().status()).isIn("COMPLETED", "FAILED");
        });
    }

    // ==== Helpers ====

    private void seedEpisodicMemories(UUID tenantId, int count, int ageDays) {
        Instant past = Instant.now().minus(ageDays, ChronoUnit.DAYS);
        for (int i = 0; i < count; i++) {
            Memory m = new Memory();
            m.setTenantId(tenantId);
            m.setContent("Test episodic memory " + i + " for tenant " + tenantId);
            m.setEmbedding(new float[1536]); // Dummy embedding
            m.setMemoryType(MemoryType.EPISODIC);
            m.setSourceMemoryIds(Collections.emptyList());
            m.setCreatedAt(past);
            m.setLastAccessedAt(past);
            m.setConsolidated(false);
            memoryRepository.save(m);
        }
    }

    private void stubOpenAiSuccess() {
        stubOpenAiEmbeddingSuccess();
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(generateChatResponseJson())));
    }

    private void stubOpenAiEmbeddingSuccess() {
        String embeddingJson = generateEmbeddingResponseJson();
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(embeddingJson)));
    }

    private String generateEmbeddingResponseJson() {
        StringBuilder embedding = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            if (i > 0) embedding.append(",");
            embedding.append("0.01");
        }
        embedding.append("]");
        // The "usage" block is required — Spring AI's parser calls usage.promptTokens()
        // and throws NullPointerException if it is absent.
        return """
                {
                  "object": "list",
                  "data": [{ "object": "embedding", "index": 0, "embedding": %s }],
                  "model": "text-embedding-ada-002",
                  "usage": { "prompt_tokens": 8, "total_tokens": 8 }
                }
                """.formatted(embedding.toString());
    }

    private String generateChatResponseJson() {
        // A complete OpenAI chat-completion payload. The "finish_reason" field is required —
        // without it, Spring AI's parser throws NullPointerException calling finishReason().name().
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "gpt-3.5-turbo",
                  "choices": [{
                    "index": 0,
                    "message": { "role": "assistant", "content": "This is a summarized semantic memory." },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
                }
                """;
    }
}
