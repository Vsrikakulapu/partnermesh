package com.partnermesh.enrollment.domain;

import static com.partnermesh.enrollment.domain.Sql.instant;
import static com.partnermesh.enrollment.domain.Sql.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EnrollmentRepository {

    private final JdbcClient jdbc;

    public EnrollmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new PENDING enrollment; returns false if the partner is already enrolled in the program. */
    public boolean insertIfAbsent(Enrollment e) {
        return jdbc.sql("""
                        INSERT INTO enrollments (id, partner_id, program_code, contact_email, status, created_at, updated_at)
                        VALUES (:id, :partnerId, :programCode, :contactEmail, :status, :createdAt, :updatedAt)
                        ON CONFLICT (partner_id, program_code) DO NOTHING
                        """)
                .param("id", e.id())
                .param("partnerId", e.partnerId())
                .param("programCode", e.programCode())
                .param("contactEmail", e.contactEmail())
                .param("status", e.status().name())
                .param("createdAt", ts(e.createdAt()))
                .param("updatedAt", ts(e.updatedAt()))
                .update() == 1;
    }

    public Optional<Enrollment> findById(UUID id) {
        return jdbc.sql("SELECT * FROM enrollments WHERE id = :id")
                .param("id", id)
                .query(EnrollmentRepository::map)
                .optional();
    }

    /** PENDING -> ACTIVE. A no-op if the enrollment already left PENDING (e.g. a duplicate delivery). */
    public boolean activate(UUID id, String provisionId, Instant now) {
        return jdbc.sql("""
                        UPDATE enrollments SET status = 'ACTIVE', provision_id = :provisionId,
                               version = version + 1, updated_at = :now
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("provisionId", provisionId)
                .param("now", ts(now))
                .update() == 1;
    }

    /** PENDING -> FAILED (the compensating action when provisioning cannot succeed). */
    public boolean fail(UUID id, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE enrollments SET status = 'FAILED', failure_reason = :reason,
                               version = version + 1, updated_at = :now
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("reason", truncate(reason))
                .param("now", ts(now))
                .update() == 1;
    }

    static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static Enrollment map(ResultSet rs, int row) throws SQLException {
        return new Enrollment(
                rs.getObject("id", UUID.class),
                rs.getString("partner_id"),
                rs.getString("program_code"),
                rs.getString("contact_email"),
                EnrollmentStatus.valueOf(rs.getString("status")),
                rs.getString("provision_id"),
                rs.getString("failure_reason"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
