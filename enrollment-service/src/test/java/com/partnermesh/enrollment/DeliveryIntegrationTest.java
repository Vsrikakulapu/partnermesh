package com.partnermesh.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class DeliveryIntegrationTest extends IntegrationTestBase {

    private final ObjectMapper json = new ObjectMapper();

    private UUID enroll(String partner) throws Exception {
        ResponseEntity<String> response = post(newKey(), enrollmentBody(partner));
        return UUID.fromString(json.readTree(response.getBody()).path("id").asText());
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(expected));
    }

    @Test
    void enrollmentIsProvisionedAndActivated() throws Exception {
        UUID id = enroll("acme");
        awaitStatus(id, "ACTIVE");

        assertThat(outboxStatus(id)).isEqualTo("DONE");
        assertThat(STUB.calls()).containsExactly(new ProvisioningStub.Call(id.toString(), id.toString()));
        String body = rest.getForObject("/api/v1/enrollments/" + id, String.class);
        assertThat(json.readTree(body).path("provisionId").asText()).isEqualTo(STUB.provisions().get(id.toString()));
    }

    @Test
    void transientFailuresAreRetriedWithTheSameIdempotencyKey() throws Exception {
        STUB.failNext(2);
        UUID id = enroll("globex");
        awaitStatus(id, "ACTIVE");

        assertThat(STUB.calls()).hasSize(3).allSatisfy(call -> assertThat(call.idempotencyKey()).isEqualTo(id.toString()));
        assertThat(outboxAttempts(id)).isEqualTo(3);
        assertThat(outboxStatus(id)).isEqualTo("DONE");
    }

    @Test
    void aLostResponseDoesNotCreateASecondProvision() throws Exception {
        STUB.dropResponseNext(1);  // downstream commits, then the reply is lost
        UUID id = enroll("initech");
        awaitStatus(id, "ACTIVE");

        assertThat(STUB.calls()).hasSize(2);
        assertThat(STUB.provisions()).hasSize(1).containsKey(id.toString());
    }

    @Test
    void exhaustedRetriesDeadLetterTheEventAndFailTheEnrollment() throws Exception {
        STUB.mode(ProvisioningStub.Mode.ALWAYS_UNAVAILABLE);
        UUID id = enroll("hooli");
        awaitStatus(id, "FAILED");

        assertThat(STUB.calls()).hasSize(4);  // max-attempts in the test profile
        assertThat(outboxStatus(id)).isEqualTo("DEAD");
        String body = rest.getForObject("/api/v1/enrollments/" + id, String.class);
        assertThat(json.readTree(body).path("failureReason").asText()).contains("gave up after 4 attempts").contains("503");
    }

    @Test
    void permanentFailuresAreNotRetried() throws Exception {
        STUB.mode(ProvisioningStub.Mode.BAD_REQUEST);
        UUID id = enroll("umbrella");
        awaitStatus(id, "FAILED");

        assertThat(STUB.calls()).hasSize(1);
        assertThat(outboxStatus(id)).isEqualTo("DEAD");
    }

    @Test
    void anEventAbandonedByACrashedInstanceIsRecoveredWhenItsLeaseExpires() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO enrollments (id, partner_id, program_code, contact_email, status, created_at, updated_at)
                        VALUES (:id, 'wayne', 'GOLD', 'ops@wayne.example', 'PENDING', :now, :now)
                        """).param("id", id).param("now", now).update();
        // Claimed by an instance that then died: IN_FLIGHT, lease already expired.
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_id, event_type, payload, status, attempts, next_attempt_at,
                                                   locked_until, lease_token, created_at, updated_at)
                        VALUES (:eventId, :id, 'PROVISION_REQUESTED', '{}', 'IN_FLIGHT', 1, :now,
                                :expired, :token, :now, :now)
                        """)
                .param("eventId", UUID.randomUUID()).param("id", id).param("now", now)
                .param("expired", now.minusSeconds(5)).param("token", UUID.randomUUID()).update();

        awaitStatus(id, "ACTIVE");
        assertThat(outboxAttempts(id)).isEqualTo(2);
        assertThat(STUB.calls()).hasSize(1);
    }
}
