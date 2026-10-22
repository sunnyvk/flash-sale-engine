package com.sunny.flashsale.booking.saga;

import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.ApiException;
import com.sunny.flashsale.common.events.MalformedEventException;
import com.sunny.flashsale.common.events.PaymentEvent;
import com.sunny.flashsale.common.events.PaymentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentOutcomeHandlerTest {

    @Mock BookingService bookings;

    private PaymentOutcomeHandler handler() {
        return new PaymentOutcomeHandler(bookings);
    }

    private static PaymentEvent event(PaymentEventType type, String reason) {
        return new PaymentEvent("e-1", type, Instant.parse("2026-10-18T05:00:00Z"), "b-1", "alice", new BigDecimal("100.00"), reason);
    }

    @Test
    @DisplayName("PAYMENT_SUCCEEDED confirms the booking")
    void succeededConfirms() {
        handler().handle(event(PaymentEventType.PAYMENT_SUCCEEDED, null));

        verify(bookings).confirm("b-1");
    }

    @Test
    @DisplayName("PAYMENT_FAILED cancels the booking with the reason: the compensating action that frees the seat")
    void failedCancels() {
        handler().handle(event(PaymentEventType.PAYMENT_FAILED, "Card declined"));

        verify(bookings).cancel("b-1", "payment failed: Card declined");
    }

    @Test
    @DisplayName("PAYMENT_REFUNDED is only a receipt: the booking is not touched")
    void refundedIsReceipt() {
        handler().handle(event(PaymentEventType.PAYMENT_REFUNDED, "payment timeout"));

        verifyNoInteractions(bookings);
    }

    @Test
    @DisplayName("late payment (booking already cancelled): swallowed, not retried, because payment-service refunds it")
    void latePaymentIsNotAnError() {
        doThrow(ApiException.conflict(BookingErrors.INVALID_BOOKING_STATE, "Booking is CANCELLED, cannot confirm"))
                .when(bookings).confirm("b-1");

        assertThatCode(() -> handler().handle(event(PaymentEventType.PAYMENT_SUCCEEDED, null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("payment for a booking that does not exist can never succeed: MalformedEventException, straight to the DLT")
    void unknownBooking() {
        doThrow(ApiException.notFound(BookingErrors.BOOKING_NOT_FOUND, "nope")).when(bookings).confirm("b-1");
        doThrow(ApiException.notFound(BookingErrors.BOOKING_NOT_FOUND, "nope")).when(bookings).cancel("b-1", "payment failed: x");

        assertThatThrownBy(() -> handler().handle(event(PaymentEventType.PAYMENT_SUCCEEDED, null)))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> handler().handle(event(PaymentEventType.PAYMENT_FAILED, "x")))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    @DisplayName("any other failure propagates so the message is retried")
    void otherFailuresRetry() {
        doThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "BOOM", "boom")).when(bookings).confirm("b-1");

        assertThatThrownBy(() -> handler().handle(event(PaymentEventType.PAYMENT_SUCCEEDED, null)))
                .isInstanceOf(ApiException.class);
    }
}
