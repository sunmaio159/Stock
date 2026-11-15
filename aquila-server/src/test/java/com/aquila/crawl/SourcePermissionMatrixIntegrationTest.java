package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sourcepolicy.Permission;
import com.aquila.sourcepolicy.SourcePolicy;
import java.time.Instant;
import java.util.List;
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
 * Falsifying tests for finding C2: the six licensed-source permissions must be checked
 * individually, and the display path must re-check them at the point of use (§8.1).
 * A source that is ACTIVE but lacks one capability must be refused for that capability
 * only.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SourcePermissionMatrixIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-06T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SourcePolicy policy;
    @Autowired private SourceReadService read;
    @Autowired private CrawlApplicationService crawl;
    @Autowired private FixtureSourceAdapter adapter;

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
        CrawlFixture.clear(jdbc);
        adapter.clearPlans();
    }

    @Test
    void eachPermissionIsCheckedIndependently() {
        for (Permission granted : Permission.values()) {
            String sourceCode = "ONLY-" + granted.name();
            CrawlFixture.insertSourceWithGrants(jdbc, sourceCode, granted);

            assertThat(policy.permits(granted, sourceCode))
                    .as("granted permission %s", granted)
                    .isTrue();
            for (Permission other : Permission.values()) {
                if (other != granted) {
                    assertThat(policy.permits(other, sourceCode))
                            .as("withheld permission %s on %s", other, sourceCode)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void revokedSourceIsRefusedForEveryPermission() {
        CrawlFixture.insertRevokedSource(jdbc, "FIXTURE-REVOKED");

        for (Permission permission : Permission.values()) {
            assertThat(policy.permits(permission, "FIXTURE-REVOKED"))
                    .as("revoked source, permission %s", permission)
                    .isFalse();
        }
    }

    @Test
    void unconfiguredSourceIsRefused() {
        assertThatThrownBy(() -> policy.require(Permission.ACCESS, "NOT-CONFIGURED"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void coverageQueryIsRefusedWithoutDisplayGrant() {
        CrawlFixture.insertSourceWithGrants(jdbc, "NO-DISPLAY",
                Permission.ACCESS, Permission.STORE_RAW, Permission.STORE_NORMALIZED, Permission.ANALYZE);

        assertThatThrownBy(() -> read.coverageFacts("NO-DISPLAY", 0, 50))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void sourceListOmitsSourcesWithoutDisplayGrant() {
        CrawlFixture.insertSourceWithGrants(jdbc, "HIDDEN", Permission.ACCESS);

        List<String> visible = read.displayableSources().stream()
                .map(SourceConfigDto::sourceCode)
                .toList();

        assertThat(visible).contains(CrawlFixture.SOURCE_CODE).doesNotContain("HIDDEN");
    }

    @Test
    void collectionIsRefusedWithoutAnalyzeGrant() {
        CrawlFixture.insertSourceWithGrants(jdbc, "NO-ANALYZE",
                Permission.ACCESS, Permission.STORE_RAW, Permission.STORE_NORMALIZED, Permission.DISPLAY);
        adapter.registerPlan("NO-ANALYZE",
                FixtureSourcePlan.traceable(1, 1, CrawlFixture.ADAPTER_CONTRACT));

        assertThatThrownBy(() -> crawl.runCollection("NO-ANALYZE", "DEFAULT", START, END))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
