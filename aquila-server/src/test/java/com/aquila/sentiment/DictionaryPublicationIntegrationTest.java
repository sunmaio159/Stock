package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.governance.GovernanceFixture;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sentiment.SentimentModel.DictionarySnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Parameter-free dictionary snapshot publication against real MySQL 8.4. The service stores
 * whatever content the caller hands it; these tests lock the SHAPE of a published snapshot
 * (immutable per version, content-addressed, typed entries, explicit calibration) and do NOT
 * assert any PO-owned term, threshold or weight. The snapshot used here is the explicitly
 * labelled SMOKE fixture — non-production, uncalibrated, evaluation-not-met.
 *
 * <p>This closes the brief §7.3 gap "词典快照发布服务": previously no code wrote
 * {@code m5_dict_version}/{@code m5_dict_entry}; the only rows came from the test fixture.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DictionaryPublicationIntegrationTest {

    private static final String SMOKE_VERSION = "dict-SMOKE-20261117";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DictionarySnapshotPublisher publisher;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        SentimentFixture.clear(jdbc);
        GovernanceFixture.reset(jdbc);
    }

    @AfterEach
    void cleanup() {
        SentimentFixture.clear(jdbc);
        GovernanceFixture.clear(jdbc);
    }

    /** The PO-signed smoke snapshot: gain/decline scores, a negation, a degree, a contrast marker. */
    private static DictionarySnapshot smoke() {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("gain", new BigDecimal("40"));
        scores.put("decline", new BigDecimal("-40"));
        return new DictionarySnapshot(
                SMOKE_VERSION, scores, Set.of("not"),
                Map.of("sharply", new BigDecimal("1.5")), false, Set.of("but"));
    }

    @Test
    void publishInsertsVersionAndTypedEntries() {
        boolean inserted = publisher.publish(smoke());

        assertThat(inserted).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT entry_count FROM m5_dict_version WHERE dict_version = ?",
                Integer.class, SMOKE_VERSION)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT calibration_state FROM m5_dict_version WHERE dict_version = ?",
                String.class, SMOKE_VERSION)).isEqualTo("EXPERIMENTAL");
        String hash = jdbc.queryForObject(
                "SELECT content_hash FROM m5_dict_version WHERE dict_version = ?",
                String.class, SMOKE_VERSION);
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(countEntries("POSITIVE", "gain")).isEqualTo(1);
        assertThat(countEntries("NEGATIVE", "decline")).isEqualTo(1);
        assertThat(countEntries("NEGATION", "not")).isEqualTo(1);
        assertThat(countEntries("DEGREE", "sharply")).isEqualTo(1);
        assertThat(countEntries("CONTRAST", "but")).isEqualTo(1);
    }

    @Test
    void identicalReplayIsNoOp() {
        publisher.publish(smoke());

        assertThat(publisher.publish(smoke())).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM m5_dict_version WHERE dict_version = ?",
                Integer.class, SMOKE_VERSION)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m5_dict_entry", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void sameVersionWithDifferentContentIsRefused() {
        publisher.publish(smoke());
        DictionarySnapshot tampered = new DictionarySnapshot(
                SMOKE_VERSION, Map.of("gain", new BigDecimal("99")),
                Set.of(), Map.of(), false, Set.of());

        assertThatThrownBy(() -> publisher.publish(tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        // Immutable: the original entries survive untouched.
        assertThat(countEntries("POSITIVE", "gain")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT score FROM m5_dict_entry WHERE dict_version = ? AND word = 'gain'",
                BigDecimal.class, SMOKE_VERSION)).isEqualByComparingTo("40.00");
    }

    @Test
    void sameContentUnderAnotherVersionIsRefused() {
        publisher.publish(smoke());
        DictionarySnapshot sameContentOtherName = new DictionarySnapshot(
                "dict-SMOKE-ALIAS", smoke().wordScores(), smoke().negations(),
                smoke().degreeMultipliers(), false, smoke().contrastMarkers());

        assertThatThrownBy(() -> publisher.publish(sameContentOtherName))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void calibratedFlagDrivesCalibrationState() {
        DictionarySnapshot calibrated = new DictionarySnapshot(
                "dict-SMOKE-CAL", smoke().wordScores(), smoke().negations(),
                smoke().degreeMultipliers(), true, smoke().contrastMarkers());

        publisher.publish(calibrated);

        assertThat(jdbc.queryForObject(
                "SELECT calibration_state FROM m5_dict_version WHERE dict_version = ?",
                String.class, "dict-SMOKE-CAL")).isEqualTo("CALIBRATED");
    }

    @Test
    void contentHashIsOrderIndependentForTheSameSnapshot() {
        Map<String, BigDecimal> forward = new LinkedHashMap<>();
        forward.put("gain", new BigDecimal("40.0"));
        forward.put("decline", new BigDecimal("-40"));
        Map<String, BigDecimal> reverse = new LinkedHashMap<>();
        reverse.put("decline", new BigDecimal("-40.00"));
        reverse.put("gain", new BigDecimal("40"));

        // scale differences and map insertion order must not change the content address.
        assertThat(publisher.contentHash(new DictionarySnapshot(
                "v-order-1", forward, Set.of("not"), Map.of(), false, Set.of())))
                .isEqualTo(publisher.contentHash(new DictionarySnapshot(
                        "v-order-2", reverse, Set.of("not"), Map.of(), false, Set.of())));
    }

    private int countEntries(String dictType, String word) {
        List<Integer> rows = jdbc.query(
                "SELECT COUNT(*) FROM m5_dict_entry WHERE dict_version = ? AND dict_type = ? AND word = ?",
                (rs, n) -> rs.getInt(1), SMOKE_VERSION, dictType, word);
        return rows.get(0);
    }
}
