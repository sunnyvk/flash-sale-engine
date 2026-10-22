package com.sunny.flashsale.booking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.common.error.ApiError;
import com.sunny.flashsale.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Security rejections happen in the filter chain, before any controller runs, so the MVC exception handler never sees
 * them. This writes the same {@link ApiError} JSON by hand, so a client parses ONE error format for 401 and 403 too.
 * The message is deliberately generic: it never says whether the token was expired, forged or malformed.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorHandler.class);

    private final ObjectMapper mapper;
    private final Clock clock;

    public SecurityErrorHandler(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        log.info("Unauthenticated request: path={} reason={}", request.getRequestURI(), ex.getClass().getSimpleName());
        response.setHeader("WWW-Authenticate", "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        log.info("Forbidden request: path={}", request.getRequestURI());
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        ApiError body = new ApiError(Instant.now(clock), status.value(), code, message, request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_KEY), List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
