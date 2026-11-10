package com.aquila.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResultTest {
    @Test
    void successEnvelopeContainsCodeMessageDataAndTraceId() {
        Result<String> result = Result.success("ready", "trace-123");

        assertThat(result.code()).isZero();
        assertThat(result.message()).isEqualTo("OK");
        assertThat(result.data()).isEqualTo("ready");
        assertThat(result.traceId()).isEqualTo("trace-123");
    }

    @Test
    void errorEnvelopeContainsNoDataAndPreservesTraceId() {
        Result<Void> result = Result.error(400, "Invalid request", "trace-456");

        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).isEqualTo("Invalid request");
        assertThat(result.data()).isNull();
        assertThat(result.traceId()).isEqualTo("trace-456");
    }
}
