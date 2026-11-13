package com.aquila.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.masterdata.SecurityCode.Parsed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityCodeTest {
    @Test
    void parsesCanonicalShanghaiCode() {
        Parsed parsed = SecurityCode.parse("SH:600000");

        assertThat(parsed.exchange()).isEqualTo("SH");
        assertThat(parsed.code()).isEqualTo("600000");
        assertThat(parsed.canonical()).isEqualTo("SH:600000");
    }

    @Test
    void parsesCanonicalShenzhenCode() {
        Parsed parsed = SecurityCode.parse("SZ:000001");

        assertThat(parsed.exchange()).isEqualTo("SZ");
        assertThat(parsed.code()).isEqualTo("000001");
        assertThat(parsed.canonical()).isEqualTo("SZ:000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "600000", "SZ000001", "sh:600000", "BJ:600000", "SH:60000",
            "SH:6000000", "SH:60000A", "SH :600000", "", "SH:600000 "
    })
    void rejectsMalformedCanonicalCodes(String value) {
        assertThatThrownBy(() -> SecurityCode.parse(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullCanonicalCode() {
        assertThatThrownBy(() -> SecurityCode.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesBareCodeOnlyWithAnExplicitMarket() {
        assertThat(SecurityCode.resolve("sh", " 600000 ").canonical()).isEqualTo("SH:600000");
        assertThat(SecurityCode.resolve("SZ", "000001").canonical()).isEqualTo("SZ:000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "AUTO", "BJ", "HK"})
    void doesNotGuessMarketFromCodePrefix(String market) {
        assertThatThrownBy(() -> SecurityCode.resolve(market, "600000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"60000", "6000000", "60000A", ""})
    void rejectsInvalidBareCodesEvenWithAMarket(String bareCode) {
        assertThatThrownBy(() -> SecurityCode.resolve("SH", bareCode))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesAliasCaseWhitespaceAndFullWidthSpace() {
        assertThat(SecurityCode.normalizeAlias(" 浦发银行 ")).isEqualTo("浦发银行");
        assertThat(SecurityCode.normalizeAlias("Pu Fa　Yin Hang")).isEqualTo("pufayinhang");
        assertThat(SecurityCode.normalizeAlias("600000.SH")).isEqualTo("600000.sh");
    }

    @Test
    void rejectsBlankAlias() {
        assertThatThrownBy(() -> SecurityCode.normalizeAlias("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecurityCode.normalizeAlias(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
