package com.aquila.governance;

import com.aquila.crawl.CrawlFixture;
import com.aquila.masterdata.MasterDataFixture;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixtures for Stage C Task 2 (FT-02 / FT-03): source, securities, raw records, observed docs. */
public final class GovernanceFixture {

    public static final String SOURCE_CODE = CrawlFixture.SOURCE_CODE;
    public static final LocalDate FETCHED_DATE = LocalDate.of(2026, 11, 1);
    public static final String SECURITY_A = "SH:000000";
    public static final String SECURITY_B = "SH:000001";

    private GovernanceFixture() {}

    public static void reset(JdbcTemplate jdbc) {
        clearGovernance(jdbc);
        CrawlFixture.reset(jdbc);
        MasterDataFixture.insertSecurities(jdbc, 2);
    }

    public static void clear(JdbcTemplate jdbc) {
        clearGovernance(jdbc);
        CrawlFixture.clear(jdbc);
    }

    /** Child-first cleanup; the current-revision FK is a nullable cycle, so null it first. */
    private static void clearGovernance(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM m3_processing_job");
        jdbc.update("UPDATE m4_normalized_doc SET current_doc_revision_id = NULL");
        jdbc.update("DELETE FROM m4_recompute_marker");
        jdbc.update("DELETE FROM m4_entity_mention");
        jdbc.update("DELETE FROM m4_doc_quality");
        jdbc.update("DELETE FROM m4_doc_revision");
        jdbc.update("DELETE FROM m4_normalized_doc");
    }

    public static String insertRawRecord(
            JdbcTemplate jdbc, String outerId, String payloadHash) {
        String rawRecordId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO m3_raw_record
                    (raw_record_id, source_code, outer_id, fetched_date, payload_hash,
                     license_version, parse_version, payload, received_at, created_at)
                VALUES (?, ?, ?, ?, ?, 1, 'fixture-1.0', '{}', NOW(6), NOW(6))
                """, rawRecordId, SOURCE_CODE, outerId, Date.valueOf(FETCHED_DATE), payloadHash);
        return rawRecordId;
    }

    public static ObservedDocument observed(
            String outerId, Long sourceRevisionOrder, String bodyHash,
            String rawRecordId, String... securityCodes) {
        List<ObservedDocument.Mention> mentions = Arrays.stream(securityCodes)
                .map(code -> new ObservedDocument.Mention(code, 1))
                .toList();
        return new ObservedDocument(
                SOURCE_CODE, outerId, sourceRevisionOrder, bodyHash, rawRecordId,
                FETCHED_DATE,
                Instant.parse("2026-11-01T01:00:00Z"),
                Instant.parse("2026-11-01T02:00:00Z"),
                "normalizer-1.0", "parser-1.0", mentions);
    }
}
