package com.sunny.flashsale.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Stand-in for Razorpay/Stripe: succeeds unless the user is listed in the demo switches. */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final GatewayProperties properties;

    public SimulatedPaymentGateway(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void charge(String idempotencyKey, String userId, BigDecimal amount) {
        if (properties.outage().contains(userId)) {
            throw new PaymentGatewayException("Simulated provider outage for user " + userId);
        }
        if (properties.declined().contains(userId)) {
            throw new PaymentDeclinedException("Card declined");
        }
        log.info("Charged: key={} userId={} amount={}", idempotencyKey, userId, amount);
    }

    @Override
    public void refund(String idempotencyKey, BigDecimal amount) {
        log.info("Refunded: key={} amount={}", idempotencyKey, amount);
    }
}
