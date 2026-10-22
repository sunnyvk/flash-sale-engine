package com.sunny.flashsale.booking.api;

import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.idempotency.BookingResult;
import com.sunny.flashsale.booking.idempotency.IdempotentBookingService;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.common.error.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface. Who is calling comes ONLY from the verified JWT (its subject), never from a header or the body: a
 * client-supplied user id would let anyone book as anyone. Access rules:
 * <ul>
 *   <li>book: any logged-in user, for themselves;</li>
 *   <li>read or cancel a booking: its owner, or an ADMIN;</li>
 *   <li>confirm: ADMIN only (see SecurityConfig). The saga confirms through the service after payment succeeds;
 *       this route exists for manual operations;</li>
 *   <li>availability: public.</li>
 * </ul>
 * Asking for somebody else's booking returns 404, not 403, so ids cannot be probed to learn which ones exist.
 */
@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private final BookingService bookingService;
    private final IdempotentBookingService idempotentBookingService;

    public BookingController(BookingService bookingService, IdempotentBookingService idempotentBookingService) {
        this.bookingService = bookingService;
        this.idempotentBookingService = idempotentBookingService;
    }

    /**
     * A retry with the same Idempotency-Key returns the original booking with the same 201 status, plus the header
     * {@code Idempotent-Replayed: true}, so a client cannot tell a replay from the first answer except by that header.
     */
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> book(Authentication auth,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody BookSeatRequest request) {
        BookingResult result = idempotentBookingService.book(auth.getName(), request.eventId(), request.seatNumber(), idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(BookingResponse.from(result.booking()));
    }

    @GetMapping("/bookings/{id}")
    public BookingResponse get(@PathVariable String id, Authentication auth) {
        return BookingResponse.from(ownedBy(auth, bookingService.get(id)));
    }

    @PostMapping("/bookings/{id}/confirm")
    public BookingResponse confirm(@PathVariable String id) {
        return BookingResponse.from(bookingService.confirm(id));
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingResponse cancel(@PathVariable String id, Authentication auth,
                                  @Valid @RequestBody(required = false) CancelRequest request) {
        ownedBy(auth, bookingService.get(id));   // 404 unless the caller owns it (or is ADMIN)
        String reason = (request == null || request.reason() == null) ? "cancelled by caller" : request.reason();
        return BookingResponse.from(bookingService.cancel(id, reason));
    }

    /** Seat counts per status; the load test uses it to prove held + booked never exceeds the seat total. */
    @GetMapping("/events/{eventId}/availability")
    public BookingService.Availability availability(@PathVariable long eventId) {
        return bookingService.availability(eventId);
    }

    private static Booking ownedBy(Authentication auth, Booking booking) {
        boolean admin = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch("ROLE_ADMIN"::equals);
        if (!admin && !booking.getUserId().equals(auth.getName())) {
            throw ApiException.notFound(BookingErrors.BOOKING_NOT_FOUND, "Booking " + booking.getId() + " not found");
        }
        return booking;
    }
}
