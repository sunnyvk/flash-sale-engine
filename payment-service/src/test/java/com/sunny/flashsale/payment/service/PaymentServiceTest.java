package com.sunny.flashsale.payment.service;

import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.BookingEventType;
import com.sunny.flashsale.payment.gateway.PaymentDeclinedException;
import com.sunny.flashsale.payment.gateway.PaymentGateway;
import com.sunny.flashsale.payment.gateway.PaymentGatewayException;
import com.sunny.flashsale.payment.repository.PaymentRepository;
import com.sunny.flashsale.payment.repository.PaymentRepository.PaymentRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Mock PaymentRepository payments;
    @Mock PaymentRecorder recorder;
    @Mock PaymentGateway gateway;

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private PaymentService service() {
        return new PaymentService(payments, recorder, gateway, registry);
    }

    private static BookingEvent event(BookingEventType type, String reason) {
        return new BookingEvent("e-1", type, Instant.parse("2026-10-18T05:00:00Z"), "b-1", "alice", 1L, 42L, AMOUNT, reason);
    }

    private void paymentIs(String status) {
        when(payments.find("b-1")).thenReturn(Optional.of(new PaymentRow("b-1", "alice", AMOUNT, status)));
    }

    // ------------------------------------------------------------------ BOOKING_HELD

    @Test
    @DisplayName("HELD: claims the payment, charges once, records SUCCEEDED")
    void heldSucceeds() {
        paymentIs("PENDING");
        when(recorder.recordOutcome("b-1", "alice", AMOUNT, true, null)).thenReturn(true);

        service().handle(event(BookingEventType.BOOKING_HELD, null));

        verify(payments).insertPendingIfNew("b-1", "alice", AMOUNT);
        verify(gateway).charge("b-1", "alice", AMOUNT);
        assertThat(registry.counter("flashsale.payments", "outcome", "succeeded").count()).isEqualTo(1.0);
        verify(recorder).recordOutcome("b-1", "alice", AMOUNT, true, null);
        verify(gateway, never()).refund(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("HELD, card declined: a business failure is recorded as FAILED (this triggers the compensation), not retried")
    void heldDeclined() {
        paymentIs("PENDING");
        doThrow(new PaymentDeclinedException("Card declined")).when(gateway).charge("b-1", "alice", AMOUNT);

        service().handle(event(BookingEventType.BOOKING_HELD, null));

        verify(recorder).recordOutcome("b-1", "alice", AMOUNT, false, "Card declined");
    }

    @Test
    @DisplayName("HELD, provider outage: the exception propagates so the message is retried, nothing is recorded")
    void heldOutagePropagates() {
        paymentIs("PENDING");
        doThrow(new PaymentGatewayException("provider down")).when(gateway).charge("b-1", "alice", AMOUNT);

        assertThatThrownBy(() -> service().handle(event(BookingEventType.BOOKING_HELD, null)))
                .isInstanceOf(PaymentGatewayException.class);

        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("HELD redelivered after the payment already finished: no second charge")
    void heldDuplicateIgnored() {
        paymentIs("SUCCEEDED");

        service().handle(event(BookingEventType.BOOKING_HELD, null));

        verifyNoInteractions(gateway, recorder);
    }

    @Test
    @DisplayName("HELD, but the booking was cancelled while charging: the money is given back")
    void chargeLostRaceAgainstCancellation() {
        paymentIs("PENDING");
        when(recorder.recordOutcome("b-1", "alice", AMOUNT, true, null)).thenReturn(false);

        service().handle(event(BookingEventType.BOOKING_HELD, null));

        verify(gateway).refund("b-1", AMOUNT);
    }

    // ------------------------------------------------------------------ BOOKING_CANCELLED

    @Test
    @DisplayName("CANCELLED after a successful payment: refund, record REFUNDED")
    void cancelledRefunds() {
        paymentIs("SUCCEEDED");
        when(recorder.recordRefund("b-1", "alice", AMOUNT, "payment timeout")).thenReturn(true);

        service().handle(event(BookingEventType.BOOKING_CANCELLED, "payment timeout"));

        verify(gateway).refund("b-1", AMOUNT);
        verify(recorder).recordRefund("b-1", "alice", AMOUNT, "payment timeout");
    }

    @Test
    @DisplayName("CANCELLED while the payment is still pending: stop it, no refund needed")
    void cancelledStopsPending() {
        paymentIs("PENDING");

        service().handle(event(BookingEventType.BOOKING_CANCELLED, "payment timeout"));

        verify(recorder).cancelPending("b-1", "booking cancelled");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("CANCELLED after a failed payment (the normal compensation path): nothing to undo")
    void cancelledAfterFailureIsNoop() {
        paymentIs("FAILED");

        service().handle(event(BookingEventType.BOOKING_CANCELLED, "payment failed: Card declined"));

        verifyNoInteractions(gateway, recorder);
    }

    @Test
    @DisplayName("CANCELLED for a booking that has no payment: nothing to undo")
    void cancelledWithoutPayment() {
        when(payments.find("b-1")).thenReturn(Optional.empty());

        service().handle(event(BookingEventType.BOOKING_CANCELLED, "x"));

        verifyNoInteractions(gateway, recorder);
    }

    @Test
    @DisplayName("refund provider outage propagates, so the cancellation is retried and the refund is never forgotten")
    void refundOutagePropagates() {
        paymentIs("SUCCEEDED");
        doThrow(new PaymentGatewayException("down")).when(gateway).refund("b-1", AMOUNT);

        assertThatThrownBy(() -> service().handle(event(BookingEventType.BOOKING_CANCELLED, "x")))
                .isInstanceOf(PaymentGatewayException.class);

        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("CONFIRMED needs nothing from payment-service")
    void confirmedIsIgnored() {
        service().handle(event(BookingEventType.BOOKING_CONFIRMED, null));

        verifyNoInteractions(payments, gateway, recorder);
    }
}
