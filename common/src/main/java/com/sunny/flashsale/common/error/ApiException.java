package com.sunny.flashsale.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected, business-level failures (seat taken, duplicate request, not found...).
 * Subclassing {@link RuntimeException} means Spring's {@code @Transactional} rolls back by default,
 * which is what we want when a booking rule is violated halfway through a unit of work.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
