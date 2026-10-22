package com.sunny.flashsale.payment.service;

import com.sunny.flashsale.common.events.PaymentEventType;
import com.sunny.flashsale.payment.outbox.OutboxWriter;
import com.sunny.flashsale.payment.repository.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The only place payment status changes are made, each one in a short database transaction together with its outbox
 * event. A separate bean (not methods of PaymentService) so the @Transactional proxy applies, and so the slow
 * gateway call in PaymentService never runs while a database transaction is open.
 */
@Component
public class PaymentRecorder {

    private final PaymentRepository payments;
    private final OutboxWriter outbox;

    public PaymentRecorder(PaymentRepository payments, OutboxWriter outbox) {
        this.payments = payments;
        this.outbox = outbox;
    }

    /** @return false if the payment was no longer PENDING, so nothing was recorded or announced */
    @Transactional
    public boolean recordOutcome(String bookingId, String userId, BigDecimal amount, boolean success, String failureReason) {
        int changed = payments.complete(bookingId, success ? "SUCCEEDED" : "FAILED", failureReason);
        if (changed == 0) {
            return false;
        }
        outbox.append(success ? PaymentEventType.PAYMENT_SUCCEEDED : PaymentEventType.PAYMENT_FAILED,
                bookingId, userId, amount, failureReason);
        return true;
    }

    /** The booking was cancelled before the charge finished: stop it. No event, the booking already knows. */
    @Transactional
    public boolean cancelPending(String bookingId, String reason) {
        return payments.complete(bookingId, "FAILED", reason) == 1;
    }

    @Transactional
    public boolean recordRefund(String bookingId, String userId, BigDecimal amount, String reason) {
        if (payments.markRefunded(bookingId, reason) == 0) {
            return false;
        }
        outbox.append(PaymentEventType.PAYMENT_REFUNDED, bookingId, userId, amount, reason);
        return true;
    }
}
