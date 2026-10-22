package com.sunny.flashsale.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One physical seat. The row is the unit of contention: every locking strategy in phase 2
 * is just a different way of letting exactly one transaction move it AVAILABLE -> HELD.
 * <p>
 * Timestamps are {@link LocalDateTime} in UTC (the app runs on a UTC clock and Hibernate is set to
 * jdbc.time_zone=UTC) because it maps cleanly to MySQL DATETIME under {@code ddl-auto: validate}.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "seat_number", nullable = false, length = 16)
    private String seatNumber;

    // Explicit VARCHAR: without it Hibernate 6 may expect a native MySQL ENUM column and fail schema validation.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private SeatStatus status;

    @Column(name = "held_by", length = 64)
    private String heldBy;

    @Column(name = "held_until")
    private LocalDateTime heldUntil;

    /** Used only by the OPTIMISTIC strategy; the other strategies bump it manually so all three stay consistent. */
    @Version
    @Column(nullable = false)
    private long version;

    protected Seat() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public String getHeldBy() {
        return heldBy;
    }

    public LocalDateTime getHeldUntil() {
        return heldUntil;
    }

    public long getVersion() {
        return version;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    /** Domain rule lives here so the strategies cannot forget the invariant (HELD needs holder + expiry). */
    public void hold(String userId, LocalDateTime until) {
        if (!isAvailable()) {
            throw new IllegalStateException("Seat " + id + " is " + status + ", cannot be held");
        }
        this.status = SeatStatus.HELD;
        this.heldBy = userId;
        this.heldUntil = until;
    }
}
