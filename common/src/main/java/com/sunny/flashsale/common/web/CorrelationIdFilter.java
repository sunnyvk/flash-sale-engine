package com.sunny.flashsale.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts a correlation id in the logging MDC for the duration of a request, and echoes it in the response.
 * With JSON logging every log line of one request carries the same id, which is what makes a
 * 50-thread race debuggable. The same id will later travel in Kafka headers (phase 4).
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    // Accept only short, boring ids from the client. Anything else is replaced, which blocks log injection
    // (newlines / control characters in a header ending up in a log line).
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming != null && SAFE_ID.matcher(incoming).matches()) ? incoming : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled; a leaked MDC value would be attributed to the next request.
            MDC.remove(MDC_KEY);
        }
    }
}
