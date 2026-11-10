package com.aquila.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTest {
    private final TotpService totp = new TotpService();

    @Test
    void generatesTheSixDigitRfc6238Sha1CodeForTheThirtySecondCounter() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertThat(totp.generateCode(secret, Instant.ofEpochSecond(59))).isEqualTo("287082");
    }

    @Test
    void acceptsOnlyTheCurrentStepAndOneAdjacentThirtySecondStep() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        Instant current = Instant.ofEpochSecond(90);

        assertThat(totp.verifyCode(secret, totp.generateCode(secret, current.minusSeconds(30)), current)).isTrue();
        assertThat(totp.verifyCode(secret, totp.generateCode(secret, current), current)).isTrue();
        assertThat(totp.verifyCode(secret, totp.generateCode(secret, current.plusSeconds(30)), current)).isTrue();
        assertThat(totp.verifyCode(secret, totp.generateCode(secret, current.plusSeconds(60)), current)).isFalse();
    }
}
