package com.sunny.flashsale.payment.service;

import com.sunny.flashsale.common.events.PaymentEventType;
import com.sunny.flashsale.payment.outbox.OutboxWriter;
import com.sunny.flashsale.payment.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRecorderTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Mock PaymentRepository payments;
    @Mock OutboxWriter outbox;

    @Test
    @DisplayName("success: status updated and PAYMENT_SUCCEEDED written to the outbox")
    void success() {
        when(payments.complete("b-1", "SUCCEEDED", null)).thenReturn(1);

        assertThat(new PaymentRecorder(payments, outbox).recordOutcome("b-1", "alice", AMOUNT, true, null)).isTrue();

        verify(outbox).append(PaymentEventType.PAYMENT_SUCCEEDED, "b-1", "alice", AMOUNT, null);
    }

    @Test
    @DisplayName("failure: status FAILED with the reason, PAYMENT_FAILED carries the reason")
    void failure() {
        when(payments.complete("b-1", "FAILED", "Card declined")).thenReturn(1);

        assertThat(new PaymentRecorder(payments, outbox).recordOutcome("b-1", "alice", AMOUNT, false, "Card declined")).isTrue();

        verify(outbox).append(PaymentEventType.PAYMENT_FAILED, "b-1", "alice", AMOUNT, "Card declined");
    }

    @Test
    @DisplayName("payment no longer pending (0 rows updated): nothing is announced")
    void noLongerPending() {
        when(payments.complete("b-1", "SUCCEEDED", null)).thenReturn(0);

        assertThat(new PaymentRecorder(payments, outbox).recordOutcome("b-1", "alice", AMOUNT, true, null)).isFalse();

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("refund: REFUNDED recorded once and announced; a second refund does nothing")
    void refund() {
        when(payments.markRefunded("b-1", "timeout")).thenReturn(1, 0);
        PaymentRecorder recorder = new PaymentRecorder(payments, outbox);

        assertThat(recorder.recordRefund("b-1", "alice", AMOUNT, "timeout")).isTrue();
        assertThat(recorder.recordRefund("b-1", "alice", AMOUNT, "timeout")).isFalse();

        verify(outbox, org.mockito.Mockito.times(1)).append(PaymentEventType.PAYMENT_REFUNDED, "b-1", "alice", AMOUNT, "timeout");
    }

    @Test
    @DisplayName("cancelling a pending payment announces nothing (the booking already knows)")
    void cancelPending() {
        when(payments.complete("b-1", "FAILED", "booking cancelled")).thenReturn(1);

        assertThat(new PaymentRecorder(payments, outbox).cancelPending("b-1", "booking cancelled")).isTrue();

        verifyNoInteractions(outbox);
    }
}
