package com.aquila.governance;

import com.aquila.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised by {@link LifecycleGuard} when a derived write must not be committed.
 * {@code blockedState} is one of the non-retryable job states admitted by
 * {@code chk_m3_job_state} in V4: EXPIRED (version moved) or REVOKED (lifecycle/permission).
 */
public final class LifecycleBlockedException extends ApiException {

    private final String blockedState;

    public LifecycleBlockedException(String blockedState, String message) {
        super(HttpStatus.CONFLICT, message);
        this.blockedState = blockedState;
    }

    public String blockedState() {
        return blockedState;
    }
}
