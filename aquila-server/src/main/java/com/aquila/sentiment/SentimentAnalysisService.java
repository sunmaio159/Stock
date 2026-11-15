package com.aquila.sentiment;

import com.aquila.governance.DocRef;
import com.aquila.governance.LifecycleBlockedException;
import com.aquila.governance.LifecycleGuard;
import com.aquila.sentiment.SentimentModel.SentimentResult;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only production entry point for persisting a document-level sentiment result
 * (Stage D review 2026-11-15, C-3). A sentiment result is a derived write exactly like a
 * Stage C derived commit, so the same barriers run in the SAME transaction that writes
 * the row: the document lifecycle and captured lifecycle version, the licensed-source
 * status, grants and validity window, the content fingerprint, and the revision status.
 * A blocked commit leaves no result row behind.
 *
 * <p>As-built contract for Stage D Task 1: a revision must already be PROCESSED by the
 * Stage C governance worker before sentiment analysis may write its result — analysis
 * consumes validated content. OBSERVED (not yet processed), SUPERSEDED and anything else
 * fail closed.
 *
 * <p>The caller supplies the lifecycle version it observed when it loaded the content, the
 * same fail-closed pattern the processing job uses: a version that moved since the load is
 * an EXPIRED outcome rather than a silent overwrite. The test seam mirrors
 * ProcessingJobWorker: production never installs a hook; a hook simulating a concurrent
 * revoke must use a separate physical connection.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SentimentAnalysisService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LifecycleGuard lifecycleGuard;
    private final SentimentStore store;

    /** Test seam: runs after the entry reads, inside the analysis transaction. */
    private volatile Runnable beforeCommitHook;

    public SentimentAnalysisService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            LifecycleGuard lifecycleGuard,
            SentimentStore store) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.lifecycleGuard = lifecycleGuard;
        this.store = store;
    }

    /** Test-only seam (see ProcessingJobWorker.setBeforeCommitHook). Pass null to clear. */
    public void setBeforeCommitHook(Runnable hook) {
        this.beforeCommitHook = hook;
    }

    /**
     * Persists one document-level result behind the full Stage C barrier. Returns
     * {@code true} when the row was inserted and {@code false} on an identical replay.
     *
     * @throws LifecycleBlockedException when any barrier fails; nothing is written
     */
    public boolean persistResult(
            String engineLevel, SentimentResult result, long capturedLifecycleVersion) {
        // Entry gate outside the transaction: identity columns only, no barrier state.
        DocRef ref = resolveDocRef(result.docRevisionId());
        Boolean inserted = transactions.execute(status -> {
            Runnable hook = beforeCommitHook;
            if (hook != null) {
                hook.run();
            }
            lifecycleGuard.assertCommittable(ref, capturedLifecycleVersion);
            lifecycleGuard.assertContentUnchanged(ref.docRevisionId());
            requireProcessedRevision(ref.docRevisionId());
            return store.persistResult(engineLevel, result);
        });
        return Boolean.TRUE.equals(inserted);
    }

    /** Current-read status gate: only a governance-processed revision may be analysed. */
    private void requireProcessedRevision(String docRevisionId) {
        List<String> statuses = jdbc.query("""
                SELECT status FROM m4_doc_revision
                WHERE doc_revision_id = ? FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getString(1), docRevisionId);
        if (statuses.size() != 1 || !"PROCESSED".equals(statuses.get(0))) {
            String actual = statuses.isEmpty() ? "MISSING" : statuses.get(0);
            throw new LifecycleBlockedException(
                    "REVOKED", "Revision is " + actual + ", not PROCESSED");
        }
    }

    /** Identity resolution, same shape as ProcessingJobWorker.resolveDocRef. */
    private DocRef resolveDocRef(String docRevisionId) {
        List<DocRef> refs = jdbc.query("""
                SELECT r.doc_id, r.doc_revision_id, d.source_code, r.revision_order
                FROM m4_doc_revision r
                JOIN m4_normalized_doc d ON d.doc_id = r.doc_id
                WHERE r.doc_revision_id = ?
                """, (resultSet, rowNumber) -> new DocRef(
                        resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getLong(4)),
                docRevisionId);
        if (refs.isEmpty()) {
            throw new LifecycleBlockedException("REVOKED", "Document revision is missing");
        }
        return refs.get(0);
    }
}
