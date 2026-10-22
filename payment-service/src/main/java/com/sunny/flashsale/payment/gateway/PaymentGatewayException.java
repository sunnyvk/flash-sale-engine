package com.sunny.flashsale.payment.gateway;

/** The provider could not be reached or errored. The outcome is unknown, so the message must be retried. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }
}
