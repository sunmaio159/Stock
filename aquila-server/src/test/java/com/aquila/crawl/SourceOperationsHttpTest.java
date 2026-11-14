package com.aquila.crawl;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.AdminApi;
import com.aquila.common.GlobalExceptionHandler;
import com.aquila.common.Result;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sourcepolicy.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Falsifying tests for round-2 finding F-5: the permission matrix was only exercised at the
 * service layer, so {@code SourceController} could have been reverted to a bare
 * {@code JdbcTemplate} read and every test would have stayed green. These cases drive the
 * real controller, so the delegation through {@link SourceReadService} — and therefore
 * through the DISPLAY grant — is what is actually under test.
 *
 * <p>Only the Stage B session gate is replaced, by a pass-through that runs the supplied
 * action directly. It asserts nothing about that gate; the assertions are about the status
 * code the endpoint returns when the licence does not permit the read.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SourceOperationsHttpTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CrawlApplicationService crawl;
    @Autowired private SourceReadService read;
    @Autowired private GlobalExceptionHandler advice;
    @Autowired private FixtureSourceAdapter adapter;

    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void setUp() {
        CrawlFixture.reset(jdbc);
        mvc = MockMvcBuilders
                .standaloneSetup(new SourceController(crawl, new PassThroughAdminApi(), read))
                .setControllerAdvice(advice)
                .build();
    }

    @AfterEach
    void cleanup() {
        CrawlFixture.clear(jdbc);
        adapter.clearPlans();
    }

    @Test
    void coverageEndpointIsForbiddenWithoutDisplayGrant() throws Exception {
        CrawlFixture.insertSourceWithGrants(jdbc, "NO-DISPLAY",
                Permission.ACCESS, Permission.STORE_RAW, Permission.STORE_NORMALIZED, Permission.ANALYZE);

        mvc.perform(get("/api/sources/NO-DISPLAY/coverage"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sourcesEndpointOmitsSourcesWithoutDisplayGrant() throws Exception {
        CrawlFixture.insertSourceWithGrants(jdbc, "HIDDEN", Permission.ACCESS);

        mvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("HIDDEN"))))
                .andExpect(content().string(containsString(CrawlFixture.SOURCE_CODE)));
    }

    /** Runs the endpoint action without the Stage B admin session, which is not under test. */
    private static final class PassThroughAdminApi extends AdminApi {
        PassThroughAdminApi() {
            super(null, null);
        }

        @Override
        public <T> Result<T> respond(HttpServletRequest request, Supplier<T> action) {
            return Result.success(action.get(), null);
        }
    }
}
