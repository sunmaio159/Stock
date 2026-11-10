package com.aquila.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RecoveryCodeServiceTest {
    private final RecoveryCodeService recoveryCodes = new RecoveryCodeService();

    @Test
    void issuesTenIndependentBase32CodesForSixteenRandomBytesAndKeepsOnlyHashesAtRest() {
        var codes = recoveryCodes.generateBatch();

        assertThat(codes).hasSize(10);
        assertThat(codes.stream().map(RecoveryCodeService.IssuedCode::clearCode))
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[A-Z2-7]{26}"));
        assertThat(codes).allSatisfy(code -> {
            assertThat(code.codeHash()).hasSize(32);
            assertThat(HexFormat.of().formatHex(code.codeHash())).isNotEqualTo(code.clearCode());
        });
    }
}
