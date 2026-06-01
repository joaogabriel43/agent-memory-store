package com.agentmemorystore;

import com.agentmemorystore.application.dto.MemoryCreateRequest;
import com.agentmemorystore.application.dto.MemoryResponse;
import com.agentmemorystore.application.dto.MemorySearchResponse;
import com.agentmemorystore.application.dto.MemoryStatsResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

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
    void resetState() {
        wireMockServer.resetAll();
        // Reset circuit breakers so the intentional-failure scenario cannot leak an OPEN state
        // into subsequent ordered tests (test isolation).
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
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

    // ========== Soft delete semantics ==========

    @Test
    @Order(6)
    @DisplayName("Delete is a soft delete (deleted_at filled), cross-tenant returns 404, second delete returns 404")
    void shouldSoftDeleteAndEnforce404Semantics() {
        stubOpenAiSuccess();

        // Store a memory as Tenant A
        MemoryCreateRequest request = new MemoryCreateRequest("Memory to be soft-deleted");
        HttpEntity<MemoryCreateRequest> storeEntity = new HttpEntity<>(request, tenantHeaders(TENANT_A));
        ResponseEntity<MemoryResponse> storeResponse = restTemplate.postForEntity(
                "/api/v1/memories", storeEntity, MemoryResponse.class
        );
        UUID memoryId = storeResponse.getBody().id();

        // Deleting as another tenant must not touch the row and must return 404
        ResponseEntity<String> crossTenantDelete = restTemplate.exchange(
                "/api/v1/memories/" + memoryId,
                HttpMethod.DELETE, new HttpEntity<>(tenantHeaders(TENANT_B)), String.class
        );
        assertThat(crossTenantDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Confirm the row is still present and NOT soft-deleted after the cross-tenant attempt
        assertThat(deletedAtFor(memoryId)).isNull();

        // First delete by the owner succeeds with 204
        ResponseEntity<Void> firstDelete = restTemplate.exchange(
                "/api/v1/memories/" + memoryId,
                HttpMethod.DELETE, new HttpEntity<>(tenantHeaders(TENANT_A)), Void.class
        );
        assertThat(firstDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Soft delete: the row still exists in the table, but deleted_at is now populated
        assertThat(deletedAtFor(memoryId)).isNotNull();

        // The memory is no longer retrievable
        ResponseEntity<String> getAfterDelete = restTemplate.exchange(
                "/api/v1/memories/" + memoryId,
                HttpMethod.GET, new HttpEntity<>(tenantHeaders(TENANT_A)), String.class
        );
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Deleting a second time must return 404 (already deleted)
        ResponseEntity<String> secondDelete = restTemplate.exchange(
                "/api/v1/memories/" + memoryId,
                HttpMethod.DELETE, new HttpEntity<>(tenantHeaders(TENANT_A)), String.class
        );
        assertThat(secondDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== Search parameter validation ==========

    @Test
    @Order(7)
    @DisplayName("Search rejects non-positive limit and blank query with 400")
    void shouldReturn400ForInvalidSearchParameters() {
        HttpEntity<Void> entity = new HttpEntity<>(tenantHeaders(TENANT_A));

        // limit = 0 is not positive
        assertThat(restTemplate.exchange(
                "/api/v1/memories/search?query=anything&limit=0",
                HttpMethod.GET, entity, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        // limit negative
        assertThat(restTemplate.exchange(
                "/api/v1/memories/search?query=anything&limit=-5",
                HttpMethod.GET, entity, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        // blank query (empty value — avoids RestTemplate double-encoding of %20)
        assertThat(restTemplate.exchange(
                "/api/v1/memories/search?query=&limit=5",
                HttpMethod.GET, entity, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);

        // missing query entirely
        assertThat(restTemplate.exchange(
                "/api/v1/memories/search?limit=5",
                HttpMethod.GET, entity, String.class).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ========== Stats for an empty tenant ==========

    @Test
    @Order(8)
    @DisplayName("Stats returns zeros and null lastConsolidationAt for a tenant with no memories")
    void shouldReturnZeroStatsForEmptyTenant() {
        UUID emptyTenant = UUID.fromString("eeee0000-0000-0000-0000-0000000000ee");

        ResponseEntity<MemoryStatsResponse> response = restTemplate.exchange(
                "/api/v1/memories/stats",
                HttpMethod.GET, new HttpEntity<>(tenantHeaders(emptyTenant)), MemoryStatsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalMemories()).isZero();
        assertThat(response.getBody().consolidatedEpisodic()).isZero();
        assertThat(response.getBody().byType()).isEmpty();
        // No consolidation job has ever run in this test → must be null, not an error
        assertThat(response.getBody().lastConsolidationAt()).isNull();
    }

    // ========== Helpers ==========

    /**
     * Reads the {@code deleted_at} column directly so soft-delete assertions verify the
     * column was populated rather than merely that the row disappeared.
     */
    private java.sql.Timestamp deletedAtFor(UUID id) {
        return jdbcClient.sql("SELECT deleted_at FROM memories WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> rs.getTimestamp("deleted_at"))
                .optional()
                .orElse(null);
    }

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
            // Locale.US forces a '.' decimal separator; the default locale (e.g. pt-BR) would
            // emit ',' producing malformed JSON that Jackson rejects ("Leading zeroes not allowed").
            embedding.append(String.format(java.util.Locale.US, "%.8f", (double) i / 1536.0));
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
