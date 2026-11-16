package com.aquila.sourcepolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.crawl.CrawlFixture;
import com.aquila.masterdata.MasterDataFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Audit 2026-11-15 (clock injection), review 2026-11-16 (discriminating boundaries):
 * the validity window must be testable against a fixed clock, and every assertion must give
 * the OPPOSITE verdict under the real clock — otherwise a no-op {@code setClock} would keep
 * the suite green. Windows are built relative to today so "real now" sits inside the window
 * for every refused case and outside it for every allowed case.
 *
 * <p>Boundary semantics: {@code [valid_from, valid_to)}; a NULL upper bound never expires.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SourcePolicyValidityWindowIntegrationTest {

    private static final String SOURCE = "WINDOW-SRC";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SourcePolicy policy;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        CrawlFixture.reset(jdbc);
    }

    @AfterEach
    void cleanup() {
        // Symmetric reset: a leaked fixed clock would silently affect other cases sharing
        // the cached Spring context (review 2026-11-16 Important-3).
        policy.setClock(Clock.systemUTC());
        CrawlFixture.clear(jdbc);
    }

    private Instant today() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS);
    }

    private void sourceWithWindow(Instant from, Instant to) {
        CrawlFixture.insertActiveSource(jdbc, SOURCE);
        // Store the UTC wall clock in DATETIME columns, matching SourceValidityWindow.readUtc;
        // a Timestamp bind would apply the JVM zone (Asia/Shanghai here) and shift boundaries.
        jdbc.update("""
                UPDATE m3_source_config SET valid_from = ?, valid_to = ? WHERE source_code = ?
                """, from == null ? null : LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                to == null ? null : LocalDateTime.ofInstant(to, ZoneOffset.UTC), SOURCE);
    }

    private void at(Instant now) {
        policy.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void insideTheWindowIsAllowed() {
        // Fixed now is inside a window in the past; real today is outside → a no-op clock
        // would refuse and the test would go red.
        sourceWithWindow(today().minus(2, ChronoUnit.DAYS), today().minus(1, ChronoUnit.DAYS));
        at(today().minus(2, ChronoUnit.DAYS).plusSeconds(1));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isTrue();
    }

    @Test
    void theLowerBoundIsInclusive() {
        sourceWithWindow(today().minus(2, ChronoUnit.DAYS), today().minus(1, ChronoUnit.DAYS));
        at(today().minus(2, ChronoUnit.DAYS));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isTrue();
    }

    @Test
    void theUpperBoundIsExclusive() {
        // Real today is INSIDE this window; only the fixed clock at the upper bound refuses.
        sourceWithWindow(today().minus(1, ChronoUnit.DAYS), today().plus(1, ChronoUnit.DAYS));
        at(today().plus(1, ChronoUnit.DAYS));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isFalse();
        assertThatThrownBy(() -> policy.require(Permission.ACCESS, SOURCE))
                .isInstanceOf(ApiException.class)
                .satisfies(cause -> assertThat(((ApiException) cause).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void beforeTheWindowIsRefused() {
        // Real today is inside; only the fixed clock before the lower bound refuses.
        sourceWithWindow(today().minus(1, ChronoUnit.DAYS), today().plus(1, ChronoUnit.DAYS));
        at(today().minus(2, ChronoUnit.DAYS));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isFalse();
    }

    @Test
    void anOpenEndedWindowNeverExpires() {
        // Fixed now (2030) is after the lower bound; real today is before it, so a no-op
        // clock would refuse this open-ended window and the test would go red.
        sourceWithWindow(today().plus(10, ChronoUnit.DAYS), null);
        at(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isTrue();
    }
}
