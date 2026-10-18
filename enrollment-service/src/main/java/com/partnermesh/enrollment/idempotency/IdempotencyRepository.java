package com.partnermesh.enrollment.idempotency;

import static com.partnermesh.enrollment.domain.Sql.ts;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT idempotency_key, request_hash, response_status, response_body
                        FROM idempotency_keys WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, row) -> new IdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        rs.getString("response_body")))
                .optional();
    }

    /** Claims the key. A concurrent insert of the same key waits on the unique index, then sees the conflict. */
    public boolean claim(String key, String requestHash, Instant now) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (idempotency_key, request_hash, response_status, response_body, created_at)
                        VALUES (:key, :hash, 0, '', :now)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", ts(now))
                .update() == 1;
    }

    public void complete(String key, int status, String body, UUID enrollmentId) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET response_status = :status, response_body = :body, enrollment_id = :enrollmentId
                        WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .param("status", status)
                .param("body", body)
                .param("enrollmentId", enrollmentId)
                .update();
    }
}
