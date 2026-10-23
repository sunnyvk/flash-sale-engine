package com.sunny.flashsale.common.error;

import com.sunny.flashsale.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Translates every exception into {@link ApiError}.
 * <p>
 * Extends {@link ResponseEntityExceptionHandler} so Spring's own MVC errors (405 wrong method, 415 wrong media type,
 * 400 unreadable JSON, 404 no route...) keep their correct status. A bare {@code @ExceptionHandler(Exception.class)}
 * would swallow them and turn a harmless 404 into a 500, which would also pollute error-rate dashboards.
 * <ul>
 *   <li>Expected business failures ({@link ApiException}) are logged at INFO without a stack trace.</li>
 *   <li>A database connection pool that stays exhausted returns 503 SERVICE_BUSY with Retry-After (overload, not a bug).</li>
 *   <li>Anything unexpected returns a generic 500 body; the real message only goes to the log,
 *       so we never leak SQL, class names or hostnames to a client.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        log.info("Request rejected: code={} status={} message={}", ex.getCode(), ex.getStatus().value(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(body(ex.getStatus(), ex.getCode(), ex.getMessage(), request.getRequestURI(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (isConnectionPoolExhausted(ex)) {
            // Overload, not a bug: every database connection was busy for the whole wait. Say so with 503 + Retry-After
            // so clients (and load balancers) back off and retry, instead of reading it as "the server is broken" (500).
            // One WARN line without a stack trace: under a flood this would otherwise write hundreds of stack traces.
            log.warn("Rejected request, database connection pool exhausted: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                            "The service is busy, please retry shortly", request.getRequestURI(), List.of()));
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error",
                        request.getRequestURI(), List.of()));
    }

    /**
     * HikariCP signals "waited connectionTimeout for a free connection" with java.sql.SQLTransientConnectionException,
     * wrapped by Hibernate and Spring (which classes live in modules this library does not depend on), so we look
     * through the cause chain for the JDK type instead of for the Spring wrapper types.
     */
    static boolean isConnectionPoolExhausted(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    /** Bean-validation failures on a {@code @Valid @RequestBody}: 400 with one detail line per bad field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.status(status)
                .body(body(status, "VALIDATION_FAILED", "Request validation failed", path(request), details));
    }

    /** Every other Spring MVC error funnels through here; we only swap the body for our {@link ApiError}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             @Nullable Object ignoredBody,
                                                             HttpHeaders headers,
                                                             HttpStatusCode status,
                                                             WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Server error handled by MVC infrastructure", ex);
        }
        String code = codeFor(status);
        return ResponseEntity.status(status).headers(headers)
                .body(body(status, code, messageFor(status), path(request), List.of()));
    }

    private ApiError body(HttpStatusCode status, String code, String message, String path, List<String> details) {
        return new ApiError(
                Instant.now(clock),
                status.value(),
                code,
                message,
                path,
                MDC.get(CorrelationIdFilter.MDC_KEY),
                details);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI() : "";
    }

    private static String codeFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "HTTP_" + status.value() : known.name();   // e.g. METHOD_NOT_ALLOWED
    }

    private static String messageFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "HTTP " + status.value() : known.getReasonPhrase();
    }
}
