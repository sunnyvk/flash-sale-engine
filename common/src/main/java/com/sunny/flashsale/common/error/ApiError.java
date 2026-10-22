package com.sunny.flashsale.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The one error shape every service returns, so clients (and k6 checks) parse a single format.
 * {@code code} is a stable machine-readable string; {@code message} is for humans and may change.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<String> details) {
}
