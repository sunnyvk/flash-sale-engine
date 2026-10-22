package com.sunny.flashsale.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Demo switches for the simulated provider.
 *
 * @param declinedUserIds users whose card is always declined (business failure)
 * @param outageUserIds   users whose charge always hits a provider outage (technical failure)
 */
@ConfigurationProperties(prefix = "payment.gateway")
public record GatewayProperties(List<String> declinedUserIds, List<String> outageUserIds) {

    public GatewayProperties {
        declinedUserIds = declinedUserIds == null ? List.of() : List.copyOf(declinedUserIds);
        outageUserIds = outageUserIds == null ? List.of() : List.copyOf(outageUserIds);
    }

    Set<String> declined() {
        return Set.copyOf(declinedUserIds);
    }

    Set<String> outage() {
        return Set.copyOf(outageUserIds);
    }
}
