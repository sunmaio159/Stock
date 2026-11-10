package com.aquila.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquila.common.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new HealthController())
            .addFilters(new TraceIdFilter())
            .build();

    @Test
    void healthIsAvailableAtApiPathWithServerGeneratedTraceId() throws Exception {
        mvc.perform(get("/api/health").header("X-Trace-Id", "caller-controlled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f-]{36}")))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader("X-Trace-Id"))
                        .matches("[0-9a-f-]{36}")
                        .isNotEqualTo("caller-controlled"));
    }
}
