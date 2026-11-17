package com.aquila.sentiment;

import com.aquila.common.ApiException;
import com.aquila.sentiment.SentimentModel.DictionarySnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes a versioned dictionary snapshot into {@code m5_dict_version}/{@code m5_dict_entry}.
 * This is the parameter-free publishing mechanism left open in brief §7.3: the service decides
 * NO business value — every term, score, negation, degree, contrast marker and calibration
 * state arrives inside the caller-supplied {@link DictionarySnapshot}. Before PO sign-off there
 * was nothing to publish; the PO has now signed the口径 but chosen a non-production SMOKE
 * dictionary, so this service is used to publish that explicitly uncalibrated snapshot into the
 * TEST database only (never production, never online publication).
 *
 * <p>Three invariants, enforced in one transaction so a half-published snapshot is impossible:
 *
 * <ul>
 *   <li>Content-addressed: {@code content_hash} is a deterministic SHA-256 over a canonical
 *       form of the whole snapshot (sorted keys, scale-normalised numbers). The DB unique key
 *       makes the same content addressable by exactly one version.
 *   <li>Immutable per version: replaying an identical snapshot is a no-op; reusing a version
 *       name with different content is refused with 409 (deterministic inputs cannot differ).
 *   <li>Explicit calibration: {@code calibration_state} is derived only from the snapshot's
 *       {@code calibrated} flag — never defaulted to CALIBRATED.
 * </ul>
 *
 * <p>{@link #contentHash} is public and deterministic so a caller can compute the address before
 * publishing; it is exercised directly by the integration test (order/scale independence).
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class DictionarySnapshotPublisher {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DictionarySnapshotPublisher(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * Publishes the snapshot. Returns {@code true} on first insert, {@code false} on an identical
     * replay. A version name reused with different content is refused with 409.
     */
    public boolean publish(DictionarySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String version = snapshot.dictVersion();
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("dictVersion is required");
        }
        String hash = contentHash(snapshot);

        return transactions.execute(status -> {
            String existingHash = jdbc.query(
                    "SELECT content_hash FROM m5_dict_version WHERE dict_version = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getString(1) : null, version);
            if (existingHash != null) {
                if (hash.equals(existingHash)) {
                    return false; // identical replay
                }
                throw conflict("Dictionary version is immutable: " + version
                        + " already stores different content");
            }
            String hashOwner = jdbc.query(
                    "SELECT dict_version FROM m5_dict_version WHERE content_hash = ?",
                    rs -> rs.next() ? rs.getString(1) : null, hash);
            if (hashOwner != null) {
                throw conflict("Dictionary content is already published as version " + hashOwner);
            }

            List<Entry> entries = entries(snapshot);
            jdbc.update("""
                    INSERT INTO m5_dict_version
                        (dict_version, content_hash, entry_count, calibration_state,
                         published_at, created_at)
                    VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, version, hash, entries.size(),
                    snapshot.calibrated() ? "CALIBRATED" : "EXPERIMENTAL");

            for (Entry entry : entries) {
                jdbc.update("""
                        INSERT INTO m5_dict_entry
                            (dict_version, dict_type, word, score, created_at)
                        VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                        """, version, entry.type(), entry.word(), entry.score());
            }
            return true;
        });
    }

    /**
     * A deterministic, content-addressed digest of the snapshot CONTENT. The version name is
     * intentionally EXCLUDED: content-addressing means identical content shares one address
     * regardless of the name a caller tries to give it, which is what lets the DB unique key
     * refuse the same content published under a second version. Numbers are normalised with
     * {@link BigDecimal#stripTrailingZeros()} so 40 / 40.0 / 40.00 share one address; maps and
     * sets are canonicalised in the snapshot constructor (TreeMap/TreeSet), so iteration order
     * is already deterministic here.
     */
    public String contentHash(DictionarySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder canonical = new StringBuilder();
        canonical.append("dict|1|\n");
        appendMap(canonical, "score", snapshot.wordScores());
        appendSet(canonical, "neg", snapshot.negations());
        appendMap(canonical, "deg", snapshot.degreeMultipliers());
        appendSet(canonical, "con", snapshot.contrastMarkers());
        canonical.append("cal=").append(snapshot.calibrated()).append('\n');
        return sha256(canonical.toString());
    }

    private static void appendMap(StringBuilder out, String kind, Map<String, BigDecimal> map) {
        for (var entry : map.entrySet()) {
            out.append(kind).append(':').append(entry.getKey()).append('=')
                    .append(num(entry.getValue())).append('\n');
        }
    }

    private static void appendSet(StringBuilder out, String kind, Set<String> set) {
        for (String value : set) {
            out.append(kind).append(':').append(value).append('\n');
        }
    }

    private static String num(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Derives typed entries from the snapshot fields; no term/score is invented here. */
    private static List<Entry> entries(DictionarySnapshot snapshot) {
        List<Entry> result = new ArrayList<>();
        snapshot.wordScores().forEach((word, score) -> {
            String type = score.signum() < 0 ? "NEGATIVE" : "POSITIVE";
            result.add(new Entry(type, word, score));
        });
        snapshot.negations().forEach(word -> result.add(new Entry("NEGATION", word, BigDecimal.ZERO)));
        snapshot.degreeMultipliers().forEach((word, value) -> result.add(new Entry("DEGREE", word, value)));
        snapshot.contrastMarkers().forEach(word -> result.add(new Entry("CONTRAST", word, BigDecimal.ZERO)));
        return result;
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    private record Entry(String type, String word, BigDecimal score) {}
}
