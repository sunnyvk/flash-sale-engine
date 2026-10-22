package com.sunny.flashsale.payment.gateway;

/** The provider said no. Retrying will not change the answer. */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
