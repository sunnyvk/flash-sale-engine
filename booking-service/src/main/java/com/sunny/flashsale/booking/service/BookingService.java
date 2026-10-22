package com.sunny.flashsale.booking.service;

import com.sunny.flashsale.booking.config.BookingProperties;
import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.domain.BookingStatus;
import com.sunny.flashsale.booking.domain.FlashEvent;
import com.sunny.flashsale.booking.outbox.OutboxWriter;
import com.sunny.flashsale.booking.domain.Seat;
import com.sunny.flashsale.booking.repository.BookingRepository;
import com.sunny.flashsale.booking.repository.FlashEventRepository;
import com.sunny.flashsale.booking.repository.SeatRepository;
import com.sunny.flashsale.booking.repository.SeatStatusCount;
import com.sunny.flashsale.common.error.ApiException;
import com.sunny.flashsale.common.events.BookingEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Seat booking use cases. Every public method is one database transaction, which is the whole point:
 * "hold the seat" and "insert the booking" commit together or not at all, so a failed insert can never
 * leave a seat stuck in HELD with no booking pointing at it.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    // Short, boring keys only: they end up in logs and (phase 3) in Redis keys.
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final FlashEventRepository events;
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final Map<LockingStrategy, SeatHolder> holders = new EnumMap<>(LockingStrategy.class);
    private final BookingProperties properties;
    private final Clock clock;
    private final OutboxWriter outbox;

    public BookingService(FlashEventRepository events,
                          SeatRepository seats,
                          BookingRepository bookings,
                          List<SeatHolder> seatHolders,
                          BookingProperties properties,
                          Clock clock,
                          OutboxWriter outbox) {
        this.events = events;
        this.seats = seats;
        this.bookings = bookings;
        this.properties = properties;
        this.clock = clock;
        this.outbox = outbox;
        seatHolders.forEach(holder -> holders.put(holder.strategy(), holder));
    }

    /** Books using the strategy from configuration. */
    @Transactional
    public Booking book(String userId, long eventId, String seatNumber, String idempotencyKey) {
        return book(userId, eventId, seatNumber, idempotencyKey, properties.lockingStrategy());
    }

    /**
     * @param seatNumber null/blank means "any available seat" (FOR UPDATE SKIP LOCKED, strategy-independent)
     * @param strategy   how to win a specific seat; lets tests and benchmarks compare strategies side by side
     *                   without restarting the app. Also annotated @Transactional: the call above is a
     *                   self-invocation that bypasses the Spring proxy, so the outer annotation does the work.
     */
    @Transactional
    public Booking book(String userId, long eventId, String seatNumber, String idempotencyKey, LockingStrategy strategy) {
        validateIdempotencyKey(idempotencyKey);

        FlashEvent event = events.findById(eventId)
                .orElseThrow(() -> ApiException.notFound(BookingErrors.EVENT_NOT_FOUND, "Event " + eventId + " not found"));

        LocalDateTime holdUntil = LocalDateTime.now(clock).plus(properties.holdTtl());
        long seatId = (seatNumber == null || seatNumber.isBlank())
                ? holdAnySeat(eventId, userId, holdUntil)
                : holdSpecificSeat(eventId, seatNumber, userId, holdUntil, strategy);

        Booking booking = new Booking(UUID.randomUUID().toString(), userId, eventId, seatId,
                event.getPrice(), idempotencyKey);
        try {
            // saveAndFlush, not save: force the INSERT now so the unique-index backstop fires inside this method.
            bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        outbox.append(BookingEventType.BOOKING_HELD, booking, null);
        log.info("Seat held: bookingId={} eventId={} seatId={} strategy={}", booking.getId(), eventId, seatId, strategy);
        return booking;
    }

    /** Payment succeeded (called by the saga in phase 5): HELD -> BOOKED, PENDING_PAYMENT -> CONFIRMED. */
    @Transactional
    public Booking confirm(String bookingId) {
        Booking booking = find(bookingId);
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return booking; // idempotent: a retried confirmation is not an error
        }
        requirePending(booking, "confirm");
        // The seat transition is the arbiter: if cancel() or the expiry reaper already released the seat,
        // markBooked affects 0 rows and we abort before touching the booking.
        if (seats.markBooked(booking.getSeatId()) == 0) {
            throw ApiException.conflict(BookingErrors.INVALID_BOOKING_STATE, "Seat hold is no longer valid");
        }
        booking.markConfirmed();
        outbox.append(BookingEventType.BOOKING_CONFIRMED, booking, null);
        log.info("Booking confirmed: bookingId={} seatId={}", bookingId, booking.getSeatId());
        return booking;
    }

    /** Compensation step (phase 5): release the seat and cancel the booking. Safe to call twice. */
    @Transactional
    public Booking cancel(String bookingId, String reason) {
        Booking booking = find(bookingId);
        if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.EXPIRED) {
            return booking; // already compensated
        }
        requirePending(booking, "cancel");
        // Same arbiter rule as confirm(): only the transaction that moves the seat HELD -> AVAILABLE may cancel.
        // Without this check a cancel that raced a confirm could overwrite CONFIRMED with CANCELLED.
        if (seats.release(booking.getSeatId()) == 0) {
            throw ApiException.conflict(BookingErrors.INVALID_BOOKING_STATE, "Seat is no longer held for this booking");
        }
        booking.markCancelled(reason);
        outbox.append(BookingEventType.BOOKING_CANCELLED, booking, reason);
        log.info("Booking cancelled, seat released: bookingId={} seatId={} reason={}", bookingId, booking.getSeatId(), reason);
        return booking;
    }

    @Transactional(readOnly = true)
    public Booking get(String bookingId) {
        return find(bookingId);
    }

    /**
     * The booking this user already created with this key, if any. Throws IDEMPOTENCY_KEY_REUSED when the key was
     * used for a different request (other event, or other seat), because replaying it would hand back the wrong thing.
     * Known limit: a retry that omits seatNumber is only checked against the event, not "was it an any-seat request".
     */
    @Transactional(readOnly = true)
    public Optional<Booking> findExisting(String userId, String idempotencyKey, long eventId, String seatNumber) {
        Optional<Booking> existing = bookings.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        existing.ifPresent(booking -> requireSameRequest(booking, eventId, seatNumber));
        return existing;
    }

    private void requireSameRequest(Booking booking, long eventId, String seatNumber) {
        boolean sameEvent = booking.getEventId() == eventId;
        boolean sameSeat = seatNumber == null || seatNumber.isBlank()
                || seats.findIdByEventIdAndSeatNumber(eventId, seatNumber)
                .filter(seatId -> seatId.equals(booking.getSeatId())).isPresent();
        if (!sameEvent || !sameSeat) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, BookingErrors.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request");
        }
    }

    /** Seat counts per status. The load test calls this after the run: held + booked must never exceed total. */
    @Transactional(readOnly = true)
    public Availability availability(long eventId) {
        if (!events.existsById(eventId)) {
            throw ApiException.notFound(BookingErrors.EVENT_NOT_FOUND, "Event " + eventId + " not found");
        }
        long available = 0;
        long held = 0;
        long booked = 0;
        for (SeatStatusCount row : seats.countByStatus(eventId)) {
            switch (row.status()) {
                case AVAILABLE -> available = row.count();
                case HELD -> held = row.count();
                case BOOKED -> booked = row.count();
            }
        }
        return new Availability(eventId, available, held, booked);
    }

    public record Availability(long eventId, long available, long held, long booked) {
    }

    // ---------------------------------------------------------------------------------------------

    private long holdSpecificSeat(long eventId, String seatNumber, String userId, LocalDateTime until, LockingStrategy strategy) {
        long seatId = seats.findIdByEventIdAndSeatNumber(eventId, seatNumber)
                .orElseThrow(() -> ApiException.notFound(BookingErrors.SEAT_NOT_FOUND,
                        "Seat " + seatNumber + " not found for event " + eventId));
        holders.get(strategy).hold(seatId, userId, until);
        return seatId;
    }

    /**
     * SKIP LOCKED: rows another transaction has locked are skipped instead of waited for, so concurrent
     * callers each land on a different seat. Trade-off: while the last few seats are locked by in-flight
     * transactions, a caller can briefly see "none available" even though those seats are not sold yet.
     */
    private long holdAnySeat(long eventId, String userId, LocalDateTime until) {
        Seat seat = seats.lockNextAvailable(eventId).orElseThrow(BookingErrors::noSeatAvailable);
        seat.hold(userId, until);
        return seat.getId();
    }

    private Booking find(String bookingId) {
        return bookings.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound(BookingErrors.BOOKING_NOT_FOUND, "Booking " + bookingId + " not found"));
    }

    private static void requirePending(Booking booking, String action) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw ApiException.conflict(BookingErrors.INVALID_BOOKING_STATE,
                    "Booking is " + booking.getStatus() + ", cannot " + action);
        }
    }

    /** Public because the idempotency layer must validate the key BEFORE using it in a Redis key. */
    public static void validateIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.badRequest(BookingErrors.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must be 1-64 characters: letters, digits, '.', '_' or '-'");
        }
    }

    /** Turns the database's unique-index violations into the client-facing errors they represent. */
    private static RuntimeException translate(DataIntegrityViolationException e) {
        String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        if (cause.contains("uq_booking_user_idempotency")) {
            return ApiException.conflict(BookingErrors.DUPLICATE_REQUEST, "A booking with this Idempotency-Key already exists");
        }
        if (cause.contains("uq_booking_active_seat")) {
            // The application-level lock should have stopped this. If you ever see this line, a lock was bypassed
            // and the database saved us: worth an alert in phase 10.
            log.warn("DB backstop prevented a double booking (uq_booking_active_seat)");
            return BookingErrors.seatUnavailable();
        }
        return e;
    }
}
