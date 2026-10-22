package com.sunny.flashsale.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * A user's claim on a seat. The generated column {@code active_seat_id} and its unique index
 * (see V1 migration) are deliberately NOT mapped here: they are the database's own safety net,
 * and the application never needs to read or write them.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    // CHAR(36) in the schema; without SqlTypes.CHAR Hibernate expects VARCHAR and schema validation fails.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64, updatable = false)
    private String userId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    private Long seatId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "failure_reason")
    private String failureReason;

    protected Booking() {
        // JPA
    }

    public Booking(String id, String userId, Long eventId, Long seatId, BigDecimal amount, String idempotencyKey) {
        this.id = id;
        this.userId = userId;
        this.eventId = eventId;
        this.seatId = seatId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = BookingStatus.PENDING_PAYMENT;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void markConfirmed() {
        this.status = BookingStatus.CONFIRMED;
    }

    public void markCancelled(String reason) {
        this.status = BookingStatus.CANCELLED;
        this.failureReason = reason;
    }
}
