package com.sunny.flashsale.common.error;

import com.sunny.flashsale.common.web.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(fixed))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    @DisplayName("ApiException maps to its own status and stable error code")
    void apiException() throws Exception {
        mvc.perform(get("/test/conflict").header(CorrelationIdFilter.HEADER, "corr-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("SEAT_TAKEN"))
                .andExpect(jsonPath("$.message").value("Seat A-001 is already taken"))
                .andExpect(jsonPath("$.path").value("/test/conflict"))
                .andExpect(jsonPath("$.correlationId").value("corr-1"));
    }

    @Test
    @DisplayName("bean validation failures return 400 with one detail per field")
    void validationFailure() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details", hasItem(containsString("name"))));
    }

    @Test
    @DisplayName("unexpected exceptions return a generic 500 and never leak the internal message")
    void unexpectedExceptionDoesNotLeak() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message", not(containsString("jdbc"))))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("exhausted DB connection pool (wrapped in framework exceptions) returns 503 SERVICE_BUSY with Retry-After, not 500")
    void poolExhaustedIsServiceUnavailable() throws Exception {
        mvc.perform(get("/test/pool-timeout").header(CorrelationIdFilter.HEADER, "corr-2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                .andExpect(jsonPath("$.message", not(containsString("Hikari"))))
                .andExpect(jsonPath("$.correlationId").value("corr-2"));
    }

    @Test
    @DisplayName("only pool exhaustion is special: other database errors stay 500")
    void otherDatabaseErrorsStay500() throws Exception {
        mvc.perform(get("/test/sql-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("cause-chain walk: finds the pool timeout at any depth and tolerates a missing chain")
    void causeChainWalk() {
        org.junit.jupiter.api.Assertions.assertTrue(GlobalExceptionHandler.isConnectionPoolExhausted(
                new RuntimeException("a", new RuntimeException("b", new java.sql.SQLTransientConnectionException("c")))));
        org.junit.jupiter.api.Assertions.assertFalse(GlobalExceptionHandler.isConnectionPoolExhausted(new RuntimeException("x")));
        org.junit.jupiter.api.Assertions.assertFalse(GlobalExceptionHandler.isConnectionPoolExhausted(null));
    }

    @Test
    @DisplayName("Spring MVC errors keep their real status: wrong HTTP method is 405, not 500")
    void mvcErrorsKeepTheirStatus() throws Exception {
        mvc.perform(post("/test/conflict"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.path").value("/test/conflict"));
    }

    @Test
    @DisplayName("malformed JSON is a 400 client error, not a 500")
    void malformedJson() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/conflict")
        String conflict() {
            throw ApiException.conflict("SEAT_TAKEN", "Seat A-001 is already taken");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("jdbc:mysql://secret-host:3306 refused connection");
        }

        @GetMapping("/test/pool-timeout")
        String poolTimeout() {
            throw new IllegalStateException("Could not open JPA EntityManager for transaction",
                    new RuntimeException("Unable to acquire JDBC Connection",
                            new java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 3002ms")));
        }

        @GetMapping("/test/sql-error")
        String sqlError() {
            throw new IllegalStateException("query failed", new java.sql.SQLSyntaxErrorException("bad sql"));
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return payload.name();
        }
    }

    record Payload(@NotBlank String name) {
    }
}
