-- Stage D round-3 review fix (2026-11-16, Minor-1): V6's chk_m5_window_zero_samples was
-- bidirectional — it also required score IS NOT NULL whenever eligible_sample_count > 0.
-- That contradicts the decay policy: with EXPONENTIAL_HALF_LIFE and a small half-life, every
-- sample's weight can underflow to zero, leaving weightSum.signum()==0, so the aggregation
-- correctly returns score NULL while still counting the eligible mentions. The strict I-5
-- requirement is only the one-way guard "a zero-eligible window must not carry a score".
-- V6 is applied and must NOT be edited (统一方案 §7.3); this additive migration drops and
-- re-adds the constraint with the one-way predicate.

ALTER TABLE m5_sentiment_window DROP CHECK chk_m5_window_zero_samples;

ALTER TABLE m5_sentiment_window
    ADD CONSTRAINT chk_m5_window_zero_samples
    CHECK (NOT (eligible_sample_count = 0 AND score IS NOT NULL));
