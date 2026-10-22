package com.sunny.flashsale.payment.gateway;

import java.math.BigDecimal;

/**
 * The payment provider. Both calls take an idempotency key (the booking id): a real provider returns the original
 * result for a repeated key, which is what makes "charge, then crash before recording it, then charge again on retry"
 * safe. The two failure types are the heart of the saga:
 * <ul>
 *   <li>{@link PaymentDeclinedException}: a business answer ("card declined"). Final. Triggers compensation.</li>
 *   <li>{@link PaymentGatewayException}: a technical failure (timeout, provider down). Not final: the message is
 *       retried, and only after the retries are exhausted does it reach the dead-letter topic.</li>
 * </ul>
 */
public interface PaymentGateway {

    void charge(String idempotencyKey, String userId, BigDecimal amount);

    void refund(String idempotencyKey, BigDecimal amount);
}
