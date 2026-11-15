-- Stage D review fixes (2026-11-15): version-identity hardening for the V5 sentiment
-- tables. V5 is applied and must NOT be edited (统一方案 §7.3); these corrections are
-- additive ALTERs. The commit-ordered change log / version lifecycle / backfill
-- watermarks planned for "V6" in the Stage D plan move to the next migration number
-- (the physical numbering follows the applied sequence, decision D3 pattern).
--
-- Fix I-2: MySQL's default utf8mb4 collation (utf8mb4_0900_ai_ci) is case-insensitive,
-- so 'av-1' and 'AV-1' collided on one unique key while Java treated them as distinct
-- versions — the window upsert could silently merge two analysis versions. Every
-- version-identity column now uses a binary collation so database identity matches
-- Java identity exactly.
--
-- Fix I-5: a window with zero eligible samples could still carry a neutral zero score.
-- A window with no directional evidence must keep its score NULL, exactly like a
-- document-level unknown (never a disguised neutral).

-- ---------------------------------------------------------------------------
-- Version identity columns become case-sensitive (binary collation).
-- FK columns must match their referenced column exactly, so each FK is dropped,
-- both sides altered, and the FK re-added.
-- ---------------------------------------------------------------------------

-- m5_dict_version / m5_dict_entry / m5_sentiment_result.
-- Every FK referencing m5_dict_version must be dropped before its column changes
-- collation (MySQL error 3780 otherwise), so both child tables release it first.
ALTER TABLE m5_dict_entry DROP FOREIGN KEY fk_m5_dict_entry_version;
ALTER TABLE m5_sentiment_result DROP FOREIGN KEY fk_m5_result_algo;
ALTER TABLE m5_sentiment_result DROP FOREIGN KEY fk_m5_result_dict;
ALTER TABLE m5_dict_version
    MODIFY dict_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_dict_entry
    MODIFY dict_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_dict_entry
    ADD CONSTRAINT fk_m5_dict_entry_version
        FOREIGN KEY (dict_version) REFERENCES m5_dict_version (dict_version);

-- m5_algorithm_version / m5_sentiment_result (algo + dict columns).
ALTER TABLE m5_algorithm_version
    MODIFY algo_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_sentiment_result
    MODIFY algo_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_sentiment_result
    MODIFY dict_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_sentiment_result
    ADD CONSTRAINT fk_m5_result_algo
        FOREIGN KEY (algo_version) REFERENCES m5_algorithm_version (algo_version);
ALTER TABLE m5_sentiment_result
    ADD CONSTRAINT fk_m5_result_dict
        FOREIGN KEY (dict_version) REFERENCES m5_dict_version (dict_version);

-- engine_level participates in the result identity tuple, so it is binary too.
ALTER TABLE m5_sentiment_result
    MODIFY engine_level VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- m5_aggregation_policy_version / m5_sentiment_window
ALTER TABLE m5_sentiment_window DROP FOREIGN KEY fk_m5_window_policy;
ALTER TABLE m5_aggregation_policy_version
    MODIFY policy_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_sentiment_window
    MODIFY analysis_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_sentiment_window
    MODIFY aggregation_policy_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE m5_sentiment_window
    ADD CONSTRAINT fk_m5_window_policy
        FOREIGN KEY (aggregation_policy_version)
        REFERENCES m5_aggregation_policy_version (policy_version);

-- ---------------------------------------------------------------------------
-- A window with no eligible sample keeps its score NULL (I-5). A genuine neutral zero
-- with at least one eligible sample stays valid.
-- ---------------------------------------------------------------------------
ALTER TABLE m5_sentiment_window
    ADD CONSTRAINT chk_m5_window_zero_samples
    CHECK ((eligible_sample_count = 0 AND score IS NULL)
        OR (eligible_sample_count > 0 AND score IS NOT NULL));
