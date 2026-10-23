package com.sunny.flashsale.booking.idempotency;

import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Acquired;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Bypass;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.InProgress;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Mismatch;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Replay;
import com.sunny.flashsale.booking.metrics.BookingMetrics;
import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Makes "book a seat" safe to retry. Deliberately NOT @Transactional: the Redis calls must not happen while a
 * database transaction (and its row lock) is open, or a slow Redis would stretch every seat lock.
 * <p>
 * Two layers, in order:
 * <ol>
 *   <li>Redis fast path: recognise a retry without touching MySQL (replay / in-progress / mismatch).</li>
 *   <li>MySQL unique index on (user_id, idempotency_key): the real guarantee. If Redis lost the key or is down,
 *       a duplicate insert is caught and answered by looking up the booking that already exists.</li>
 * </ol>
 */
@Service
public class IdempotentBookingService {

    private final BookingService bookingService;
    private final IdempotencyStore store;
    private final BookingMetrics metrics;

    public IdempotentBookingService(BookingService bookingService, IdempotencyStore store, BookingMetrics metrics) {
        this.bookingService = bookingService;
        this.store = store;
        this.metrics = metrics;
    }

    /** Counts every attempt by outcome: the sold-out / retry / error mix is what the flash-sale dashboard shows. */
    public BookingResult book(String userId, long eventId, String seatNumber, String idempotencyKey) {
        try {
            BookingResult result = process(userId, eventId, seatNumber, idempotencyKey);
            metrics.attempt(result.replayed() ? "replayed" : "created");
            return result;
        } catch (ApiException e) {
            metrics.attempt(outcomeOf(e));
            throw e;
        } catch (RuntimeException e) {
            metrics.attempt("error");
            throw e;
        }
    }

    private static String outcomeOf(ApiException e) {
        return switch (e.getCode()) {
            case BookingErrors.SEAT_UNAVAILABLE -> "seat_unavailable";
            case BookingErrors.NO_SEAT_AVAILABLE -> "sold_out";
            case BookingErrors.REQUEST_IN_PROGRESS -> "in_progress";
            case BookingErrors.IDEMPOTENCY_KEY_REUSED -> "key_reused";
            default -> "rejected";            // validation, unknown event or seat: the client's mistake, not ours
        };
    }

    private BookingResult process(String userId, long eventId, String seatNumber, String idempotencyKey) {
        BookingService.validateIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint(eventId, seatNumber);

        return switch (store.begin(userId, idempotencyKey, fingerprint)) {
            case Acquired acquired -> {
                try {
                    BookingResult result = bookOrReplay(userId, eventId, seatNumber, idempotencyKey);
                    store.complete(userId, idempotencyKey, fingerprint, result.booking().getId());
                    yield result;
                } catch (RuntimeException e) {
                    // Failures are not remembered: releasing the key lets the client retry after e.g. SEAT_UNAVAILABLE.
                    store.abort(userId, idempotencyKey, acquired.lockValue());
                    throw e;
                }
            }
            case Replay replay -> new BookingResult(bookingService.get(replay.bookingId()), true);
            case InProgress inProgress -> throw ApiException.conflict(BookingErrors.REQUEST_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed; retry shortly");
            case Mismatch mismatch -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    BookingErrors.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was already used for a different request");
            case Bypass bypass -> bookOrReplay(userId, eventId, seatNumber, idempotencyKey);
        };
    }

    /**
     * Books; if MySQL already holds a booking for this (user, key), answers with that booking instead of failing.
     * <p>
     * Two errors can mean "this is a retry", depending on which check trips first:
     * DUPLICATE_REQUEST (the unique index on user + key fired at insert time) and SEAT_UNAVAILABLE (the retry asked for
     * the same specific seat, which our own first request is still holding, so the seat hold fails BEFORE the insert).
     * Looking the key up only on these failures keeps the happy path free of an extra query.
     */
    private BookingResult bookOrReplay(String userId, long eventId, String seatNumber, String idempotencyKey) {
        try {
            return new BookingResult(bookingService.book(userId, eventId, seatNumber, idempotencyKey), false);
        } catch (ApiException e) {
            boolean maybeRetry = BookingErrors.DUPLICATE_REQUEST.equals(e.getCode())
                    || BookingErrors.SEAT_UNAVAILABLE.equals(e.getCode());
            if (!maybeRetry) {
                throw e;
            }
            return bookingService.findExisting(userId, idempotencyKey, eventId, seatNumber)
                    .map(existing -> new BookingResult(existing, true))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Hash of what the request asks for. Same key + same fingerprint = a retry; same key + different fingerprint =
     * the client reused a key by mistake. Seat numbers are upper-cased because MySQL compares them case-insensitively.
     */
    static String fingerprint(long eventId, String seatNumber) {
        String seat = (seatNumber == null || seatNumber.isBlank()) ? "ANY" : seatNumber.trim().toUpperCase();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((eventId + "|" + seat).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
