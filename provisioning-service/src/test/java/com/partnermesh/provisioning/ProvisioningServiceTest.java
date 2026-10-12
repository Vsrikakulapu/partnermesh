package com.partnermesh.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProvisioningServiceTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FaultInjector faults;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        faults.configure(FaultInjector.Settings.NONE);
        jdbc.sql("TRUNCATE provisions").update();
    }

    private ResponseEntity<String> provision(UUID enrollmentId) {
        return rest.postForEntity("/api/v1/provisions",
                Map.of("enrollmentId", enrollmentId.toString(), "partnerId", "acme", "programCode", "GOLD"), String.class);
    }

    private long provisions() {
        return jdbc.sql("SELECT count(*) FROM provisions").query(Long.class).single();
    }

    @Test
    void repeatedCallsForOneEnrollmentReturnTheSameProvision() throws Exception {
        UUID id = UUID.randomUUID();
        ResponseEntity<String> first = provision(id);
        ResponseEntity<String> second = provision(id);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode a = json.readTree(first.getBody());
        JsonNode b = json.readTree(second.getBody());
        assertThat(a.path("provisionId").asText()).startsWith("prv-").isEqualTo(b.path("provisionId").asText());
        assertThat(a.path("created").asBoolean()).isTrue();
        assertThat(b.path("created").asBoolean()).isFalse();
        assertThat(provisions()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCallsCreateOneProvision() throws Exception {
        UUID id = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return provision(id);
                }));
            }
            start.countDown();
            List<String> provisionIds = new ArrayList<>();
            int created = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get();
                assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                created += response.getStatusCode() == HttpStatus.CREATED ? 1 : 0;
                provisionIds.add(json.readTree(response.getBody()).path("provisionId").asText());
            }
            assertThat(created).isEqualTo(1);
            assertThat(provisionIds.stream().distinct()).hasSize(1);
            assertThat(provisions()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failNextRejectsWithoutDoingWork() {
        faults.configure(new FaultInjector.Settings(0, 0, 2, 0));
        UUID id = UUID.randomUUID();
        assertThat(provision(id).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(provision(id).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(provisions()).isZero();
        assertThat(provision(id).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void droppedResponseHasAlreadyCommittedTheWork() {
        faults.configure(new FaultInjector.Settings(0, 0, 0, 1));
        UUID id = UUID.randomUUID();
        assertThat(provision(id).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(provisions()).isEqualTo(1);
        assertThat(provision(id).getStatusCode()).isEqualTo(HttpStatus.OK);  // the retry finds it
        assertThat(provisions()).isEqualTo(1);
    }

    @Test
    void faultsCanBeConfiguredOverHttp() throws Exception {
        ResponseEntity<String> response = rest.exchange("/internal/faults", HttpMethod.PUT,
                new HttpEntity<>(Map.of("failNext", 1, "latencyMs", 5)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).path("failNext").asInt()).isEqualTo(1);
        assertThat(provision(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json.readTree(rest.getForObject("/internal/stats", String.class)).path("requests").asDouble())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/provisions", Map.of("partnerId", "acme"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lookupByEnrollmentId() {
        UUID id = UUID.randomUUID();
        assertThat(rest.getForEntity("/api/v1/provisions/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        provision(id);
        assertThat(rest.getForEntity("/api/v1/provisions/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
