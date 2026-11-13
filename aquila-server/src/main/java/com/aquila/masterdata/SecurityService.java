package com.aquila.masterdata;

import com.aquila.common.ApiException;
import com.aquila.common.Page;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SecurityService {
    private static final String FILTER = """
            WHERE security_code = ?
               OR security_code IN (
                    SELECT security_code FROM m2_security_alias WHERE alias_norm = ?)
               OR security_name LIKE ? ESCAPE '\\\\'
            """;
    private static final String PROJECTION = """
            SELECT security_code, exchange, code, security_name, security_type, status
            FROM m2_security
            """;

    private final JdbcTemplate jdbc;

    public SecurityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<SecurityDto> search(String keyword, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
        String raw = keyword == null ? "" : keyword.trim();
        String canonical = raw.toUpperCase(Locale.ROOT);
        String alias = normalizeAlias(raw);
        String namePattern = "%" + escapeLike(raw) + "%";

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m2_security " + FILTER,
                Long.class, canonical, alias, namePattern);
        List<SecurityDto> items = jdbc.query(
                PROJECTION + FILTER + " ORDER BY security_code LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> new SecurityDto(
                        resultSet.getString("security_code"),
                        resultSet.getString("exchange"),
                        resultSet.getString("code"),
                        resultSet.getString("security_name"),
                        resultSet.getString("security_type"),
                        resultSet.getString("status")),
                canonical, alias, namePattern, size, (long) page * size);

        return new Page<>(items, page, size, total == null ? 0L : total);
    }

    private static String normalizeAlias(String raw) {
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return SecurityCode.normalizeAlias(raw);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
