package com.partnermesh.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import com.partnermesh.enrollment.outbox.OutboxEvent;
import com.partnermesh.enrollment.outbox.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Repository-level lease semantics, with the background poller switched off so the test owns every claim. */
@TestPropertySource(properties = "partnermesh.outbox.enabled=false")
class OutboxLeaseTest extends IntegrationTestBase {

    @Autowired
    OutboxRepository outbox;

    private UUID pendingEvent() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO enrollments (id, partner_id, program_code, contact_email, status, created_at, updated_at)
                        VALUES (:id, :partner, 'GOLD', 'ops@example.com', 'PENDING', :now, :now)
                        """).param("id", id).param("partner", "p-" + id).param("now", now).update();
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_id, event_type, payload, status, attempts, next_attempt_at,
                                                   created_at, updated_at)
                        VALUES (:eventId, :id, 'PROVISION_REQUESTED', '{}', 'PENDING', 0, :now, :now, :now)
                        """).param("eventId", UUID.randomUUID()).param("id", id).param("now", now).update();
        return id;
    }

    @Test
    void aClaimedEventIsNotClaimedAgainWhileItsLeaseIsValid() {
        pendingEvent();
        Instant now = Instant.now();
        assertThat(outbox.claimDue(now, 10, Duration.ofSeconds(30))).hasSize(1);
        assertThat(outbox.claimDue(now.plusSeconds(10), 10, Duration.ofSeconds(30))).isEmpty();
        assertThat(outbox.claimDue(now.plusSeconds(31), 10, Duration.ofSeconds(30))).hasSize(1);
    }

    @Test
    void aWorkerWhoseLeaseWasTakenOverCannotOverwriteTheNewOwner() {
        pendingEvent();
        Instant now = Instant.now();
        OutboxEvent stale = outbox.claimDue(now, 10, Duration.ofSeconds(1)).getFirst();
        OutboxEvent current = outbox.claimDue(now.plusSeconds(2), 10, Duration.ofSeconds(30)).getFirst();

        assertThat(current.id()).isEqualTo(stale.id());
        assertThat(current.attempts()).isEqualTo(2);
        assertThat(outbox.markDead(stale, "stale worker", now.plusSeconds(3))).isFalse();
        assertThat(outbox.markDone(current, now.plusSeconds(3))).isTrue();
        assertThat(outbox.countByStatus("DONE")).isEqualTo(1);
    }

    @Test
    void rescheduledEventsBecomeDueOnlyAfterTheirBackoff() {
        pendingEvent();
        Instant now = Instant.now();
        OutboxEvent event = outbox.claimDue(now, 10, Duration.ofSeconds(30)).getFirst();
        assertThat(outbox.reschedule(event, now.plusSeconds(5), "HTTP 503", now)).isTrue();

        assertThat(outbox.claimDue(now.plusSeconds(4), 10, Duration.ofSeconds(30))).isEmpty();
        List<OutboxEvent> due = outbox.claimDue(now.plusSeconds(6), 10, Duration.ofSeconds(30));
        assertThat(due).hasSize(1);
        assertThat(due.getFirst().leaseToken()).isNotEqualTo(event.leaseToken());
    }

    @Test
    void concurrentPollersNeverClaimTheSameEvent() throws Exception {
        for (int i = 0; i < 40; i++) {
            pendingEvent();
        }
        Instant now = Instant.now();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<List<OutboxEvent>>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> outbox.claimDue(now, 15, Duration.ofSeconds(30))));
            }
            var claimed = new java.util.ArrayList<UUID>();
            for (var future : futures) {
                future.get().forEach(e -> claimed.add(e.id()));
            }
            assertThat(claimed).doesNotHaveDuplicates().hasSize(40);
        } finally {
            pool.shutdownNow();
        }
    }
}
