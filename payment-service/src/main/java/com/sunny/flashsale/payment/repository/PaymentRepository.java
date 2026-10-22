package com.sunny.flashsale.payment.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Every status change is a conditional UPDATE ("... where status = 'PENDING'"), so the row count tells the caller
 * whether THIS call made the transition. That makes each step safe under redelivery and under races between the
 * charge and a concurrent cancellation, without any application-level locking.
 */
@Repository
public class PaymentRepository {

    public record PaymentRow(String bookingId, String userId, BigDecimal amount, String status) {
    }

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The unique key on booking_id makes "one payment per booking" a database fact. */
    public boolean insertPendingIfNew(String bookingId, String userId, BigDecimal amount) {
        try {
            jdbc.update("insert into payments (id, booking_id, user_id, amount, status) values (?, ?, ?, ?, 'PENDING')",
                    UUID.randomUUID().toString(), bookingId, userId, amount);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<PaymentRow> find(String bookingId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "select booking_id, user_id, amount, status from payments where booking_id = ?",
                    (rs, i) -> new PaymentRow(rs.getString("booking_id"), rs.getString("user_id"),
                            rs.getBigDecimal("amount"), rs.getString("status")),
                    bookingId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** PENDING -> SUCCEEDED or FAILED. Returns 0 if the payment was no longer pending (e.g. cancelled meanwhile). */
    public int complete(String bookingId, String newStatus, String failureReason) {
        return jdbc.update("update payments set status = ?, failure_reason = ? where booking_id = ? and status = 'PENDING'",
                newStatus, failureReason, bookingId);
    }

    /** SUCCEEDED -> REFUNDED. Returns 0 if there was nothing to refund. */
    public int markRefunded(String bookingId, String reason) {
        return jdbc.update("update payments set status = 'REFUNDED', failure_reason = ? where booking_id = ? and status = 'SUCCEEDED'",
                reason, bookingId);
    }
}
