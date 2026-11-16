package com.aquila.sourcepolicy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The single definition of a licensed source's validity window (review 2026-11-16).
 *
 * <p><strong>Storage contract:</strong> {@code m3_source_config.valid_from / valid_to} are
 * {@code DATETIME(6)} "wall-clock" columns with no timezone of their own. Every writer must
 * store the UTC wall clock ({@code LocalDateTime.ofInstant(instant, UTC)} / MySQL
 * {@code UTC_TIMESTAMP(6)}), and every reader must interpret it as UTC. That keeps the
 * business-time comparison independent of the JVM default zone and the MySQL session zone
 * (this machine runs JVM Asia/Shanghai, so a {@code NOW(6)} write measured against an
 * {@code Instant} read would otherwise drift by hours).
 *
 * <p><strong>Boundary semantics:</strong> {@code [valid_from, valid_to)} — the lower bound
 * is inclusive, the upper bound exclusive, and a NULL upper bound never expires. A missing
 * lower bound fails closed. Both the pre-write gate ({@link SourcePolicy}) and the commit
 * barrier ({@code com.aquila.governance.LifecycleGuard}) call {@link #contains}, so the two
 * checks cannot drift apart.
 */
public final class SourceValidityWindow {

    private SourceValidityWindow() {}

    /** Half-open validity window. Missing bounds fail closed. */
    public static boolean contains(Instant now, Instant validFrom, Instant validTo) {
        return validFrom != null
                && !now.isBefore(validFrom)
                && (validTo == null || now.isBefore(validTo));
    }

    /** Read a DATETIME(6) column as the UTC wall-clock instant defined above. */
    public static Instant readUtc(ResultSet resultSet, String column) throws SQLException {
        LocalDateTime value = resultSet.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
