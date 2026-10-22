package com.sunny.flashsale.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("generates a UUID when the client sends no correlation id")
    void generatesIdWhenAbsent() throws Exception {
        filter.doFilter(request, response, chain);

        String id = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(id).isNotBlank();
        assertThat(java.util.UUID.fromString(id)).isNotNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("reuses a well-formed client id so a trace can span services")
    void reusesSafeClientId() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "load-test-42");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("load-test-42");
    }

    @Test
    @DisplayName("replaces a malicious id (log injection) with a generated one")
    void replacesUnsafeClientId() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "abc\nERROR fake log line");

        filter.doFilter(request, response, chain);

        String id = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(id).doesNotContain("\n").doesNotContain("fake");
    }

    @Test
    @DisplayName("exposes the id in the MDC while the chain runs")
    void idVisibleDuringChain() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(any(), any());
        request.addHeader(CorrelationIdFilter.HEADER, "req-1");

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isEqualTo("req-1");
    }

    @Test
    @DisplayName("clears the MDC after the request, even when the chain throws")
    void clearsMdcOnFailure() throws Exception {
        doThrow(new ServletException("boom")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("does not carry an id over to the next request on the same thread")
    void doesNotLeakAcrossRequests() throws IOException, ServletException {
        request.addHeader(CorrelationIdFilter.HEADER, "first");
        filter.doFilter(request, response, chain);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
