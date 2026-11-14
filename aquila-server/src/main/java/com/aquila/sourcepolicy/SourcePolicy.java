package com.aquila.sourcepolicy;

import com.aquila.common.ApiException;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Single point that validates licensed-source permissions and their validity window
 * before any source-aware operation. All checks fail closed: an unconfigured,
 * revoked, expired, or out-of-window source is rejected instead of silently allowed.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SourcePolicy {

    private final JdbcTemplate jdbc;

    public SourcePolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Require the permission or throw {@link ApiException} (fail closed). */
    public SourcePermissionDecision require(Permission permission, String sourceCode) {
        if (permission == null || sourceCode == null || sourceCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Permission and source code are required");
        }
        SourceConfigRow row;
        try {
            row = jdbc.queryForObject(
                    """
                    SELECT permission_status, valid_from, valid_to,
                           permission_version, license_version, adapter_contract_version
                    FROM m3_source_config WHERE source_code = ?
                    """,
                    (resultSet, rowNumber) -> new SourceConfigRow(
                            resultSet.getString("permission_status"),
                            resultSet.getTimestamp("valid_from").toInstant(),
                            resultSet.getTimestamp("valid_to") == null
                                    ? null : resultSet.getTimestamp("valid_to").toInstant(),
                            resultSet.getInt("permission_version"),
                            resultSet.getInt("license_version"),
                            resultSet.getString("adapter_contract_version")),
                    sourceCode);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Source is not configured");
        }
        if (!"ACTIVE".equals(row.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Source permission is " + row.status());
        }
        Instant now = Instant.now();
        if (now.isBefore(row.validFrom())
                || (row.validTo() != null && !now.isBefore(row.validTo()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Source permission is outside its validity window");
        }
        return new SourcePermissionDecision(
                sourceCode, row.permissionVersion(), row.licenseVersion(), row.adapterContractVersion());
    }

    /** Non-throwing variant used by display/export guards. */
    public boolean permits(Permission permission, String sourceCode) {
        try {
            require(permission, sourceCode);
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    public record SourceConfigRow(
            String status, Instant validFrom, Instant validTo,
            int permissionVersion, int licenseVersion, String adapterContractVersion) {}

    public record SourcePermissionDecision(
            String sourceCode, int permissionVersion, int licenseVersion,
            String adapterContractVersion) {}
}
