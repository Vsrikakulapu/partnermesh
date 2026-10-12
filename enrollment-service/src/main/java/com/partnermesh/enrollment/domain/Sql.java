package com.partnermesh.enrollment.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC helpers: the PostgreSQL driver binds {@link OffsetDateTime} to TIMESTAMPTZ, not {@link Instant}. */
public final class Sql {

    private Sql() {
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
