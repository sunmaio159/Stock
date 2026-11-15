package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sourcepolicy.Permission;
import com.aquila.sourcepolicy.SourcePolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Audit 2026-11-15 (SourcePolicy clock injection): the validity window is a business-time
 * decision, so it must be testable against a fixed clock instead of racing the wall clock.
 * Boundary semantics locked here: {@code [valid_from, valid_to)} with an exclusive upper
 * end and an open-ended window when valid_to IS NULL.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SourcePolicyValidityWindowIntegrationTest {

    private static final Instant WINDOW_START = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-11-06T00:00:00Z");
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
        policy.setClock(Clock.systemUTC());
        CrawlFixture.clear(jdbc);
    }

    private void sourceWithWindow(Instant from, Instant to) {
        CrawlFixture.insertActiveSource(jdbc, SOURCE);
        jdbc.update("""
                UPDATE m3_source_config SET valid_from = ?, valid_to = ? WHERE source_code = ?
                """, from == null ? null : Timestamp.from(from),
                to == null ? null : Timestamp.from(to), SOURCE);
    }

    private void at(Instant now) {
        policy.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void insideTheWindowIsAllowed() {
        sourceWithWindow(WINDOW_START, WINDOW_END);
        at(Instant.parse("2026-11-05T12:00:00Z"));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isTrue();
    }

    @Test
    void theLowerBoundIsInclusive() {
        sourceWithWindow(WINDOW_START, WINDOW_END);
        at(WINDOW_START);
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isTrue();
    }

    @Test
    void theUpperBoundIsExclusive() {
        sourceWithWindow(WINDOW_START, WINDOW_END);
        at(WINDOW_END);
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isFalse();
        assertThatThrownBy(() -> policy.require(Permission.ACCESS, SOURCE))
                .isInstanceOf(ApiException.class)
                .satisfies(cause -> assertThat(((ApiException) cause).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void beforeTheWindowIsRefused() {
        sourceWithWindow(WINDOW_START, WINDOW_END);
        at(Instant.parse("2026-11-04T23:59:59Z"));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isFalse();
    }

    @Test
    void anOpenEndedWindowNeverExpires() {
        sourceWithWindow(WINDOW_START, null);
        at(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(policy.permits(Permission.ACCESS, SOURCE)).isTrue();
    }
}
