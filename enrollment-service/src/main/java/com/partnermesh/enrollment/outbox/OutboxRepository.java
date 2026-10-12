package com.partnermesh.enrollment.outbox;

import static com.partnermesh.enrollment.domain.Sql.ts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {

    public static final String PROVISION_REQUESTED = "PROVISION_REQUESTED";

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the transaction that creates the aggregate - that is the point of an outbox. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID aggregateId, String eventType, String payload, Instant now) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_id, event_type, payload, status, attempts,
                                                   next_attempt_at, created_at, updated_at)
                        VALUES (:id, :aggregateId, :eventType, :payload, 'PENDING', 0, :now, :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payload)
                .param("now", ts(now))
                .update();
    }

    /**
     * Claims due events: pending ones whose retry time has come, and in-flight ones whose lease expired
     * because the instance that owned them crashed or stalled. {@code FOR UPDATE SKIP LOCKED} lets any
     * number of replicas poll concurrently without claiming the same event.
     */
    @Transactional
    public List<OutboxEvent> claimDue(Instant now, int limit, Duration lease) {
        List<UUID> ids = jdbc.sql("""
                        SELECT id FROM outbox_events
                        WHERE (status = 'PENDING' AND next_attempt_at <= :now)
                           OR (status = 'IN_FLIGHT' AND locked_until < :now)
                        ORDER BY next_attempt_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();

        List<OutboxEvent> claimed = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            UUID token = UUID.randomUUID();
            claimed.add(jdbc.sql("""
                            UPDATE outbox_events
                            SET status = 'IN_FLIGHT', attempts = attempts + 1, lease_token = :token,
                                locked_until = :lockedUntil, updated_at = :now
                            WHERE id = :id
                            RETURNING id, aggregate_id, event_type, payload, attempts, lease_token
                            """)
                    .param("id", id)
                    .param("token", token)
                    .param("lockedUntil", ts(now.plus(lease)))
                    .param("now", ts(now))
                    .query((rs, row) -> new OutboxEvent(
                            rs.getObject("id", UUID.class),
                            rs.getObject("aggregate_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload"),
                            rs.getInt("attempts"),
                            rs.getObject("lease_token", UUID.class)))
                    .single());
        }
        return claimed;
    }

    public boolean markDone(OutboxEvent event, Instant now) {
        return finish(event, "DONE", null, now);
    }

    public boolean markDead(OutboxEvent event, String error, Instant now) {
        return finish(event, "DEAD", error, now);
    }

    /** Returns the event to PENDING with a later retry time. */
    public boolean reschedule(OutboxEvent event, Instant nextAttemptAt, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE outbox_events
                        SET status = 'PENDING', next_attempt_at = :next, locked_until = NULL, lease_token = NULL,
                            last_error = :error, updated_at = :now
                        WHERE id = :id AND status = 'IN_FLIGHT' AND lease_token = :token
                        """)
                .param("id", event.id())
                .param("token", event.leaseToken())
                .param("next", ts(nextAttemptAt))
                .param("error", truncate(error))
                .param("now", ts(now))
                .update() == 1;
    }

    private boolean finish(OutboxEvent event, String status, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE outbox_events
                        SET status = :status, locked_until = NULL, last_error = :error, updated_at = :now
                        WHERE id = :id AND status = 'IN_FLIGHT' AND lease_token = :token
                        """)
                .param("id", event.id())
                .param("token", event.leaseToken())
                .param("status", status)
                .param("error", truncate(error))
                .param("now", ts(now))
                .update() == 1;
    }

    public long countByStatus(String status) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = :status")
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
