package com.aquila.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FailureController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

    @Test
    void unexpectedExceptionReturnsSafeErrorEnvelopeWithTraceId() throws Exception {
        mvc.perform(get("/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void noResourceFoundExceptionReturnsNotFoundEnvelopeWithServerGeneratedTraceId() throws Exception {
        NoResourceController controller = new NoResourceController();
        MockMvc noResourceMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();

        noResourceMvc.perform(get("/unavailable").header(TraceIdFilter.TRACE_ID_HEADER, "caller-controlled"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER))
                        .isNotBlank()
                        .isNotEqualTo("caller-controlled")
                        .isEqualTo(result.getResponse().getContentAsString()
                                .replaceAll("(?s).*\"traceId\":\"([^\"]+)\".*", "$1")));
    }

    @RestController
    static class FailureController {
        @GetMapping("/failure")
        void fail() {
            throw new IllegalStateException("private implementation detail");
        }
    }

    @RestController
    static class NoResourceController {
        @GetMapping("/unavailable")
        void fail(HttpServletRequest request) throws NoResourceFoundException {
            throw new NoResourceFoundException(
                    HttpMethod.GET, request.getRequestURI());
        }
    }
}
