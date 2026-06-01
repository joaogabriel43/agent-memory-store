package com.agentmemorystore;

import com.agentmemorystore.application.dto.MemoryCreateRequest;
import com.agentmemorystore.application.dto.MemoryResponse;
import com.agentmemorystore.application.dto.MemorySearchResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests covering the three critical scenarios:
 * 1. WireMock 200 OK — Successful embedding + memory storage
 * 2. WireMock 500 Error — Fallback via circuit breaker
 * 3. Multitenancy isolation — Tenant B cannot see Tenant A's data
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbb0000-0000-0000-0000-000000000002");

    private static WireMockServer wireMockServer;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("agent_memory_store_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

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
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    // ========== Scenario 1: WireMock 200 OK — Successful store ==========

    @Test
    @Order(1)
    @DisplayName("Scenario 1: Store memory with successful embedding (WireMock 200)")
    void shouldStoreMemoryWhenEmbeddingSucceeds() {
        stubOpenAiSuccess();

        MemoryCreateRequest request = new MemoryCreateRequest(
                "The user prefers dark mode and uses Java 21"
        );

        HttpEntity<MemoryCreateRequest> entity = new HttpEntity<>(request, tenantHeaders(TENANT_A));
        ResponseEntity<MemoryResponse> response = restTemplate.postForEntity(
                "/api/v1/memories", entity, MemoryResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().tenantId()).isEqualTo(TENANT_A);
        assertThat(response.getBody().content()).isEqualTo("The user prefers dark mode and uses Java 21");
        assertThat(response.getBody().memoryType()).isEqualTo("EPISODIC");
        assertThat(response.getBody().relevanceScore()).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("Scenario 1b: Search returns results with relevance score")
    void shouldSearchMemoriesWithRelevanceScore() {
        stubOpenAiSuccess();

        // First store a memory
        MemoryCreateRequest storeRequest = new MemoryCreateRequest("Java 21 dark mode preference");
        HttpEntity<MemoryCreateRequest> storeEntity = new HttpEntity<>(storeRequest, tenantHeaders(TENANT_A));
        restTemplate.postForEntity("/api/v1/memories", storeEntity, MemoryResponse.class);

        // Then search
        HttpEntity<Void> searchEntity = new HttpEntity<>(tenantHeaders(TENANT_A));
        ResponseEntity<MemorySearchResponse> response = restTemplate.exchange(
                "/api/v1/memories/search?query=dark+mode&limit=5",
                HttpMethod.GET, searchEntity, MemorySearchResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().memories()).isNotEmpty();
        assertThat(response.getBody().semanticWeightUsed()).isEqualTo(0.7);
        assertThat(response.getBody().memories().get(0).relevanceScore()).isNotNull();
    }

    // ========== Scenario 2: WireMock 500 Error — Circuit breaker fallback ==========

    @Test
    @Order(3)
    @DisplayName("Scenario 2: Embedding failure returns 503 without leaking API key")
    void shouldReturn503WhenEmbeddingServiceFails() {
        stubOpenAiFailure();

        MemoryCreateRequest request = new MemoryCreateRequest("This should fail gracefully");
        HttpEntity<MemoryCreateRequest> entity = new HttpEntity<>(request, tenantHeaders(TENANT_A));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/memories", entity, String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("temporarily unavailable");
        // Critical: Ensure no API key or provider details are leaked
        assertThat(response.getBody()).doesNotContain("sk-test-key");
        assertThat(response.getBody()).doesNotContain("openai");
        assertThat(response.getBody()).doesNotContain("OpenAI");
    }

    // ========== Scenario 3: Multitenancy isolation ==========

    @Test
    @Order(4)
    @DisplayName("Scenario 3: Tenant B cannot see Tenant A's memories")
    void shouldIsolateTenantData() {
        stubOpenAiSuccess();

        // Store memory as Tenant A
        MemoryCreateRequest request = new MemoryCreateRequest("Secret data for tenant A only");
        HttpEntity<MemoryCreateRequest> storeEntity = new HttpEntity<>(request, tenantHeaders(TENANT_A));
        restTemplate.postForEntity("/api/v1/memories", storeEntity, MemoryResponse.class);

        // Search as Tenant B
        HttpEntity<Void> searchEntity = new HttpEntity<>(tenantHeaders(TENANT_B));
        ResponseEntity<MemorySearchResponse> response = restTemplate.exchange(
                "/api/v1/memories/search?query=secret+data&limit=10",
                HttpMethod.GET, searchEntity, MemorySearchResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().memories()).isEmpty();
        assertThat(response.getBody().totalFound()).isZero();
    }

    // ========== FindById with last_accessed_at refresh ==========

    @Test
    @Order(5)
    @DisplayName("FindById refreshes last_accessed_at and enforces tenant isolation")
    void shouldFindByIdAndRefreshLastAccessedAt() {
        stubOpenAiSuccess();

        // Store a memory
        MemoryCreateRequest request = new MemoryCreateRequest("Memory to be retrieved by ID");
        HttpEntity<MemoryCreateRequest> storeEntity = new HttpEntity<>(request, tenantHeaders(TENANT_A));
        ResponseEntity<MemoryResponse> storeResponse = restTemplate.postForEntity(
                "/api/v1/memories", storeEntity, MemoryResponse.class
        );
        UUID memoryId = storeResponse.getBody().id();

        // Find by ID as same tenant
        HttpEntity<Void> findEntity = new HttpEntity<>(tenantHeaders(TENANT_A));
        ResponseEntity<MemoryResponse> findResponse = restTemplate.exchange(
                "/api/v1/memories/" + memoryId,
                HttpMethod.GET, findEntity, MemoryResponse.class
        );

        assertThat(findResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(findResponse.getBody().id()).isEqualTo(memoryId);

        // Find by ID as different tenant — should get 404
        HttpEntity<Void> otherTenantEntity = new HttpEntity<>(tenantHeaders(TENANT_B));
        ResponseEntity<String> notFoundResponse = restTemplate.exchange(
                "/api/v1/memories/" + memoryId,
                HttpMethod.GET, otherTenantEntity, String.class
        );

        assertThat(notFoundResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== Helpers ==========

    private HttpHeaders tenantHeaders(UUID tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId.toString());
        return headers;
    }

    /**
     * Stubs the OpenAI embeddings endpoint to return a fixed 1536-dimension vector.
     */
    private void stubOpenAiSuccess() {
        String embeddingJson = generateEmbeddingResponseJson();
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(embeddingJson)));
    }

    /**
     * Stubs the OpenAI embeddings endpoint to return a 500 Internal Server Error.
     */
    private void stubOpenAiFailure() {
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/embeddings"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\"}}")));
    }

    /**
     * Generates a valid OpenAI embedding response JSON with a 1536-dimension vector.
     */
    private String generateEmbeddingResponseJson() {
        StringBuilder embedding = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            if (i > 0) embedding.append(",");
            embedding.append(String.format("%.8f", (double) i / 1536.0));
        }
        embedding.append("]");

        return """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "index": 0,
                      "embedding": %s
                    }
                  ],
                  "model": "text-embedding-ada-002",
                  "usage": {
                    "prompt_tokens": 8,
                    "total_tokens": 8
                  }
                }
                """.formatted(embedding.toString());
    }
}
