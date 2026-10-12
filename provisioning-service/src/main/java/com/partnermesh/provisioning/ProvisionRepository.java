package com.partnermesh.provisioning;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProvisionRepository {

    private final JdbcClient jdbc;

    public ProvisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent on enrollment id: returns true only for the request that actually created the row. */
    public boolean insertIfAbsent(Provision p) {
        return jdbc.sql("""
                        INSERT INTO provisions (enrollment_id, provision_id, partner_id, program_code, created_at)
                        VALUES (:enrollmentId, :provisionId, :partnerId, :programCode, :createdAt)
                        ON CONFLICT (enrollment_id) DO NOTHING
                        """)
                .param("enrollmentId", p.enrollmentId())
                .param("provisionId", p.provisionId())
                .param("partnerId", p.partnerId())
                .param("programCode", p.programCode())
                .param("createdAt", p.createdAt().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    public Optional<Provision> find(UUID enrollmentId) {
        return jdbc.sql("SELECT * FROM provisions WHERE enrollment_id = :id")
                .param("id", enrollmentId)
                .query(ProvisionRepository::map)
                .optional();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM provisions").query(Long.class).single();
    }

    private static Provision map(ResultSet rs, int row) throws SQLException {
        return new Provision(
                rs.getObject("enrollment_id", UUID.class),
                rs.getString("provision_id"),
                rs.getString("partner_id"),
                rs.getString("program_code"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

}
