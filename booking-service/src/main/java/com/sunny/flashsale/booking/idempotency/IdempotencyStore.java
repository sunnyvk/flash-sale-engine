package com.sunny.flashsale.booking.idempotency;

/**
 * Remembers which Idempotency-Key values have been seen, so a retried request is recognised before it reaches
 * the database. This is an optimisation layer only: the unique index on (user_id, idempotency_key) in MySQL is
 * what actually guarantees "never double-book", so every method here must be allowed to fail open.
 */
public interface IdempotencyStore {

    /** Try to claim the key for this request. */
    Begin begin(String userId, String key, String fingerprint);

    /** The request succeeded: remember the booking id so retries can be answered without touching the seat. */
    void complete(String userId, String key, String fingerprint, String bookingId);

    /** The request failed: release the claim, but only if it is still ours, so the client can retry. */
    void abort(String userId, String key, String lockValue);

    /** What {@link #begin} found. Sealed so callers must handle every case. */
    sealed interface Begin permits Acquired, Replay, InProgress, Mismatch, Bypass {
    }

    /** We own the key; go ahead and process. {@code lockValue} proves ownership when releasing. */
    record Acquired(String lockValue) implements Begin {
    }

    /** Same key and same request already completed: answer with the stored booking. */
    record Replay(String bookingId) implements Begin {
    }

    /** Same key and same request is still being processed by another call. */
    record InProgress() implements Begin {
    }

    /** Same key but a different request body: a client bug, never silently accept it. */
    record Mismatch() implements Begin {
    }

    /** Redis is unavailable or returned something unreadable: skip the fast path and rely on the database. */
    record Bypass() implements Begin {
    }
}
