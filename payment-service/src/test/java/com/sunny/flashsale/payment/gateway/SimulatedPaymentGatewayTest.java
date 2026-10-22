package com.sunny.flashsale.payment.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway =
            new SimulatedPaymentGateway(new GatewayProperties(List.of("poor"), List.of("unlucky")));

    @Test
    @DisplayName("an ordinary user is charged")
    void charges() {
        assertThatCode(() -> gateway.charge("b-1", "alice", BigDecimal.TEN)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a listed user is declined: a business failure")
    void declines() {
        assertThatThrownBy(() -> gateway.charge("b-1", "poor", BigDecimal.TEN)).isInstanceOf(PaymentDeclinedException.class);
    }

    @Test
    @DisplayName("a user hitting an outage gets a technical failure, which is a different exception so it is retried")
    void outage() {
        assertThatThrownBy(() -> gateway.charge("b-1", "unlucky", BigDecimal.TEN)).isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("null lists mean no demo failures")
    void nullLists() {
        SimulatedPaymentGateway plain = new SimulatedPaymentGateway(new GatewayProperties(null, null));
        assertThatCode(() -> plain.charge("b-1", "anyone", BigDecimal.TEN)).doesNotThrowAnyException();
    }
}
