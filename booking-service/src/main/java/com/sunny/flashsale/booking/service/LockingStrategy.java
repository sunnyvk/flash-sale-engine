package com.sunny.flashsale.booking.service;

/** The three ways phase 2 can let exactly one transaction win a seat. Chosen by BOOKING_LOCKING_STRATEGY. */
public enum LockingStrategy {
    /** One conditional UPDATE; the WHERE clause is the lock. Default: fewest round trips, shortest lock. */
    ATOMIC_UPDATE,
    /** SELECT ... FOR UPDATE, then modify. Losers queue on the row lock. */
    PESSIMISTIC,
    /** Read, modify, write guarded by a version column. Losers fail at flush time. */
    OPTIMISTIC
}
