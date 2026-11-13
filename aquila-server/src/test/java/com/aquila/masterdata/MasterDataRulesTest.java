package com.aquila.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.watchlist.CapacityPolicy;
import com.aquila.watchlist.HotlistSource;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MasterDataRulesTest {

    @ParameterizedTest
    @MethodSource("validRows")
    void parsesCanonicalImportRows(String raw, String expectedCode) {
        ImportRow row = ImportRow.parse(1, raw);

        assertThat(row.valid()).isTrue();
        assertThat(row.errorCode()).isNull();
        assertThat(row.normalizedCode()).isEqualTo(expectedCode);
        assertThat(row.rowNo()).isEqualTo(1);
        assertThat(row.rawText()).isEqualTo(raw);
    }

    @ParameterizedTest
    @MethodSource("invalidRows")
    void rejectsInvalidImportRowsWithStableErrorCodes(String raw, String expectedError) {
        ImportRow row = ImportRow.parse(7, raw);

        assertThat(row.valid()).isFalse();
        assertThat(row.normalizedCode()).isNull();
        assertThat(row.errorCode()).isEqualTo(expectedError);
    }

    @Test
    void neverGuessesMarketFromABareSixDigitCode() {
        assertThat(ImportRow.parse(1, "600000").errorCode()).isEqualTo(ImportErrorCode.MISSING_MARKET);
        assertThat(ImportRow.parse(1, "000001").errorCode()).isEqualTo(ImportErrorCode.MISSING_MARKET);
    }

    @Test
    void countsDistinctSecuritiesAcrossGroupsOnlyOnce() {
        long distinct = CapacityPolicy.distinctCount(List.of(
                List.of("SH:600000", "SZ:000001"),
                List.of("SH:600000", "SH:600001")));

        assertThat(distinct).isEqualTo(3);
    }

    @Test
    void addingAnAlreadyOwnedSecurityDoesNotConsumeCapacity() {
        assertThat(CapacityPolicy.wouldExceed(2, 2, true)).isFalse();
        assertThat(CapacityPolicy.wouldExceed(2, 2, false)).isTrue();
        assertThat(CapacityPolicy.wouldExceed(1, 2, false)).isFalse();
    }

    @Test
    void manualHotlistCannotCarryACandidateSource() {
        assertThat(HotlistSource.values()).containsExactly(HotlistSource.MANUAL);
        assertThatThrownBy(() -> HotlistSource.valueOf("CANDIDATE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> validRows() {
        return Stream.of(
                Arguments.of("SH:600000", "SH:600000"),
                Arguments.of("SZ:000001", "SZ:000001"),
                Arguments.of(" sh:600000 ", "SH:600000"),
                Arguments.of("sz:000001", "SZ:000001"));
    }

    static Stream<Arguments> invalidRows() {
        return Stream.of(
                Arguments.of("", ImportErrorCode.EMPTY_ROW),
                Arguments.of("   ", ImportErrorCode.EMPTY_ROW),
                Arguments.of("600000", ImportErrorCode.MISSING_MARKET),
                Arguments.of("BJ:600000", ImportErrorCode.INVALID_MARKET),
                Arguments.of("HK:000001", ImportErrorCode.INVALID_MARKET),
                Arguments.of("SH:60000", ImportErrorCode.INVALID_CODE),
                Arguments.of("SH:6000001", ImportErrorCode.INVALID_CODE),
                Arguments.of("SH:ABCDEF", ImportErrorCode.INVALID_CODE),
                Arguments.of("SH", ImportErrorCode.INVALID_CODE));
    }
}
