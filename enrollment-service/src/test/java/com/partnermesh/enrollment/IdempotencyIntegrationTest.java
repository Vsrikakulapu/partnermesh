package com.partnermesh.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class IdempotencyIntegrationTest extends IntegrationTestBase {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void repeatingARequestWithTheSameKeyReturnsTheOriginalResponse() throws Exception {
        String key = newKey();
        ResponseEntity<String> first = post(key, enrollmentBody("acme"));
        ResponseEntity<String> second = post(key, enrollmentBody("acme"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());

        JsonNode body = json.readTree(first.getBody());
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(count("enrollments")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void cosmeticDifferencesInARetryAreStillAReplay() {
        String key = newKey();
        post(key, Map.of("partnerId", "acme", "programCode", "GOLD", "contactEmail", "Ops@Acme.example"));
        ResponseEntity<String> retry =
                post(key, Map.of("partnerId", "acme", "programCode", "GOLD", "contactEmail", "ops@acme.EXAMPLE"));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String key = newKey();
        post(key, enrollmentBody("acme"));
        ResponseEntity<String> reused = post(key, enrollmentBody("globex"));
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody()).contains("already used with a different request body");
        assertThat(count("enrollments")).isEqualTo(1);
    }

    @Test
    void theIdempotencyKeyIsRequiredAndValidated() {
        assertThat(post(null, enrollmentBody("acme")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("short", enrollmentBody("acme")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("bad key with spaces", enrollmentBody("acme")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("enrollments")).isZero();
    }

    @Test
    void invalidBodiesAreRejectedWithoutSideEffects() {
        ResponseEntity<String> response =
                post(newKey(), Map.of("partnerId", "acme", "programCode", "GOLD", "contactEmail", "not-an-email"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("idempotency_keys")).isZero();
    }

    @Test
    void aSecondEnrollmentForTheSamePartnerAndProgramConflicts() {
        assertThat(post(newKey(), enrollmentBody("acme")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<String> duplicate = post(newKey(), enrollmentBody("acme"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("enrollments")).isEqualTo(1);
        assertThat(count("idempotency_keys")).isEqualTo(1);  // the rejected request left nothing behind
    }

    @Test
    void concurrentDuplicatesCreateExactlyOneEnrollment() throws Exception {
        String key = newKey();
        int clients = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                Callable<ResponseEntity<String>> call = () -> {
                    start.await();
                    return post(key, enrollmentBody("initech"));
                };
                futures.add(pool.submit(call));
            }
            start.countDown();

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }
            assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED));
            assertThat(responses.stream().map(ResponseEntity::getBody).distinct()).hasSize(1);
            assertThat(responses.stream().filter(r -> "false".equals(r.getHeaders().getFirst("Idempotent-Replayed"))))
                    .hasSize(1);
            assertThat(count("enrollments")).isEqualTo(1);
            assertThat(count("outbox_events")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unknownEnrollmentIs404() {
        assertThat(rest.getForEntity("/api/v1/enrollments/00000000-0000-0000-0000-000000000000", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
