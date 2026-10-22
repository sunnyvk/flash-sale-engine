package com.sunny.flashsale.booking.repository;

import com.sunny.flashsale.booking.domain.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Every way of touching a seat row in phase 2. Each method is annotated with the strategy it serves.
 * All of them must run inside a transaction opened by the service layer.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("select s.id from Seat s where s.eventId = :eventId and s.seatNumber = :seatNumber")
    Optional<Long> findIdByEventIdAndSeatNumber(@Param("eventId") Long eventId,
                                                @Param("seatNumber") String seatNumber);

    // ---- Strategy: PESSIMISTIC -> SELECT ... FOR UPDATE (blocks other writers until we commit) ----
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    // ---- "Give me any seat": FOR UPDATE SKIP LOCKED lets 1000 threads each grab a DIFFERENT row without queueing ----
    @Query(value = "select * from seats where event_id = :eventId and status = 'AVAILABLE' "
            + "order by id limit 1 for update skip locked", nativeQuery = true)
    Optional<Seat> lockNextAvailable(@Param("eventId") Long eventId);

    // ---- Strategy: ATOMIC_UPDATE -> one conditional UPDATE; the WHERE clause is the lock ----
    @Modifying(flushAutomatically = true)
    @Query("update Seat s set s.status = com.sunny.flashsale.booking.domain.SeatStatus.HELD, "
            + "s.heldBy = :userId, s.heldUntil = :until, s.version = s.version + 1 "
            + "where s.id = :id and s.status = com.sunny.flashsale.booking.domain.SeatStatus.AVAILABLE")
    int tryHold(@Param("id") Long id, @Param("userId") String userId, @Param("until") LocalDateTime until);

    // ---- Saga steps: confirm (HELD -> BOOKED) and compensation (HELD -> AVAILABLE) ----
    @Modifying(flushAutomatically = true)
    @Query("update Seat s set s.status = com.sunny.flashsale.booking.domain.SeatStatus.BOOKED, "
            + "s.heldUntil = null, s.version = s.version + 1 "
            + "where s.id = :id and s.status = com.sunny.flashsale.booking.domain.SeatStatus.HELD")
    int markBooked(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("update Seat s set s.status = com.sunny.flashsale.booking.domain.SeatStatus.AVAILABLE, "
            + "s.heldBy = null, s.heldUntil = null, s.version = s.version + 1 "
            + "where s.id = :id and s.status = com.sunny.flashsale.booking.domain.SeatStatus.HELD")
    int release(@Param("id") Long id);

    @Query("select new com.sunny.flashsale.booking.repository.SeatStatusCount(s.status, count(s)) "
            + "from Seat s where s.eventId = :eventId group by s.status")
    List<SeatStatusCount> countByStatus(@Param("eventId") Long eventId);
}
