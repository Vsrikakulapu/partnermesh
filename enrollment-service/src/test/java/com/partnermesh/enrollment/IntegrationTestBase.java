package com.partnermesh.enrollment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Runs the real application against PostgreSQL (from {@code DB_URL}, default localhost:5432/enrollment)
 * with the provisioning service replaced by {@link ProvisioningStub}.
 *
 * <p>{@code @DirtiesContext} closes each test class's context afterwards, so a background outbox poller
 * from one class can never consume events that belong to the next.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "partnermesh.outbox.poll-interval-ms=50",
        "partnermesh.outbox.lease=2s",
        "partnermesh.outbox.max-attempts=4",
        "partnermesh.outbox.backoff-base=20ms",
        "partnermesh.outbox.backoff-max=200ms",
        "partnermesh.provisioning.request-timeout=2s"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class IntegrationTestBase {

    static final ProvisioningStub STUB;

    static {
        try {
            STUB = new ProvisioningStub();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void provisioningUrl(DynamicPropertyRegistry registry) {
        registry.add("partnermesh.provisioning.base-url", STUB::baseUrl);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanState() {
        STUB.reset();
        jdbc.sql("TRUNCATE outbox_events, idempotency_keys, enrollments").update();
    }

    static Map<String, String> enrollmentBody(String partnerId) {
        return Map.of("partnerId", partnerId, "programCode", "GOLD", "contactEmail", partnerId + "@example.com");
    }

    ResponseEntity<String> post(String idempotencyKey, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.postForEntity("/api/v1/enrollments", new HttpEntity<>(body, headers), String.class);
    }

    static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    String status(UUID enrollmentId) {
        return jdbc.sql("SELECT status FROM enrollments WHERE id = :id").param("id", enrollmentId).query(String.class).single();
    }

    String outboxStatus(UUID enrollmentId) {
        return jdbc.sql("SELECT status FROM outbox_events WHERE aggregate_id = :id")
                .param("id", enrollmentId).query(String.class).single();
    }

    int outboxAttempts(UUID enrollmentId) {
        return jdbc.sql("SELECT attempts FROM outbox_events WHERE aggregate_id = :id")
                .param("id", enrollmentId).query(Integer.class).single();
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
