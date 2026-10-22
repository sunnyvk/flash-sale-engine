package com.sunny.flashsale.booking.saga;

import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.ApiException;
import com.sunny.flashsale.common.events.MalformedEventException;
import com.sunny.flashsale.common.events.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The booking side of the saga: turns payment outcomes into booking state.
 * <pre>
 *   PAYMENT_SUCCEEDED -> confirm the booking (seat HELD -> BOOKED)
 *   PAYMENT_FAILED    -> cancel the booking (seat released)   <- the compensating action
 *   PAYMENT_REFUNDED  -> nothing: the booking was already cancelled, this is just the receipt
 * </pre>
 * confirm() and cancel() are already idempotent, so a redelivered event is harmless.
 */
@Service
public class PaymentOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeHandler.class);

    private final BookingService bookings;

    public PaymentOutcomeHandler(BookingService bookings) {
        this.bookings = bookings;
    }

    public void handle(PaymentEvent event) {
        switch (event.type()) {
            case PAYMENT_SUCCEEDED -> onSucceeded(event);
            case PAYMENT_FAILED -> onFailed(event);
            case PAYMENT_REFUNDED -> log.info("Payment refunded: bookingId={} reason={}", event.bookingId(), event.reason());
        }
    }

    private void onSucceeded(PaymentEvent event) {
        try {
            bookings.confirm(event.bookingId());
        } catch (ApiException e) {
            if (BookingErrors.BOOKING_NOT_FOUND.equals(e.getCode())) {
                throw new MalformedEventException("Payment for unknown booking " + event.bookingId(), e);
            }
            if (BookingErrors.INVALID_BOOKING_STATE.equals(e.getCode())) {
                // Paid too late: the hold expired (or was cancelled) first. Not an error to retry. The cancellation
                // event told payment-service to refund, so the money goes back without any action here.
                log.warn("Payment succeeded for a booking that is no longer pending, refund is payment-service's job: "
                        + "bookingId={} reason={}", event.bookingId(), e.getMessage());
                return;
            }
            throw e;
        }
    }

    private void onFailed(PaymentEvent event) {
        try {
            bookings.cancel(event.bookingId(), "payment failed: " + event.reason());
        } catch (ApiException e) {
            if (BookingErrors.BOOKING_NOT_FOUND.equals(e.getCode())) {
                throw new MalformedEventException("Payment failure for unknown booking " + event.bookingId(), e);
            }
            throw e;
        }
    }
}
