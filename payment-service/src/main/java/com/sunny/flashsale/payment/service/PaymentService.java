package com.sunny.flashsale.payment.service;

import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.payment.gateway.PaymentDeclinedException;
import com.sunny.flashsale.payment.gateway.PaymentGateway;
import com.sunny.flashsale.payment.repository.PaymentRepository;
import com.sunny.flashsale.payment.repository.PaymentRepository.PaymentRow;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The payment side of the saga. Reacts to booking events:
 * <ul>
 *   <li>BOOKING_HELD: charge the user, then announce PAYMENT_SUCCEEDED or PAYMENT_FAILED;</li>
 *   <li>BOOKING_CANCELLED: undo whatever happened to the payment (stop it, or refund it);</li>
 *   <li>BOOKING_CONFIRMED: nothing to do.</li>
 * </ul>
 * Deliberately NOT @Transactional: the provider call can be slow, and holding a database connection open across it
 * would let a slow provider exhaust the pool. Instead each step is idempotent: the PENDING row is a claim, the
 * provider is called with the booking id as idempotency key, and the outcome is recorded with a conditional update.
 * A crash anywhere just means the message is redelivered and the steps repeat harmlessly.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentRecorder recorder;
    private final PaymentGateway gateway;
    private final MeterRegistry metrics;

    public PaymentService(PaymentRepository payments, PaymentRecorder recorder, PaymentGateway gateway, MeterRegistry metrics) {
        this.payments = payments;
        this.recorder = recorder;
        this.gateway = gateway;
        this.metrics = metrics;
    }

    public void handle(BookingEvent event) {
        switch (event.type()) {
            case BOOKING_HELD -> onHeld(event);
            case BOOKING_CANCELLED -> onCancelled(event);
            case BOOKING_CONFIRMED -> log.debug("Nothing to do for confirmed booking {}", event.bookingId());
        }
    }

    private void onHeld(BookingEvent event) {
        payments.insertPendingIfNew(event.bookingId(), event.userId(), event.amount());
        PaymentRow payment = payments.find(event.bookingId()).orElseThrow();
        if (!"PENDING".equals(payment.status())) {
            log.info("Payment already {}: duplicate or late delivery ignored, bookingId={}", payment.status(), payment.bookingId());
            return;
        }
        try {
            gateway.charge(payment.bookingId(), payment.userId(), payment.amount());
        } catch (PaymentDeclinedException e) {
            recorder.recordOutcome(payment.bookingId(), payment.userId(), payment.amount(), false, e.getMessage());
            count("declined");
            log.info("Payment declined: bookingId={} reason={}", payment.bookingId(), e.getMessage());
            return;
        }
        if (recorder.recordOutcome(payment.bookingId(), payment.userId(), payment.amount(), true, null)) {
            count("succeeded");
            log.info("Payment succeeded: bookingId={}", payment.bookingId());
        } else {
            // The booking was cancelled while we were charging: the money moved but the sale is dead. Give it back.
            gateway.refund(payment.bookingId(), payment.amount());
            count("refunded_late");
            log.warn("Charge finished after the booking was cancelled, refunded: bookingId={}", payment.bookingId());
        }
    }

    private void onCancelled(BookingEvent event) {
        PaymentRow payment = payments.find(event.bookingId()).orElse(null);
        if (payment == null) {
            log.info("Cancelled booking has no payment, nothing to undo: bookingId={}", event.bookingId());
            return;
        }
        switch (payment.status()) {
            case "PENDING" -> {
                if (recorder.cancelPending(payment.bookingId(), "booking cancelled")) {
                    log.info("Pending payment stopped: bookingId={}", payment.bookingId());
                }
            }
            case "SUCCEEDED" -> {
                gateway.refund(payment.bookingId(), payment.amount());
                if (recorder.recordRefund(payment.bookingId(), payment.userId(), payment.amount(), event.reason())) {
                    count("refunded");
                    log.info("Payment refunded: bookingId={} reason={}", payment.bookingId(), event.reason());
                }
            }
            default -> log.debug("Payment is {}, nothing to undo: bookingId={}", payment.status(), payment.bookingId());
        }
    }

    /** outcome: succeeded | declined | refunded | refunded_late (charged, then the booking was cancelled meanwhile). */
    private void count(String outcome) {
        metrics.counter("flashsale.payments", "outcome", outcome).increment();
    }
}
