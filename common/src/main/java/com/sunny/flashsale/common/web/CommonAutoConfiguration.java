package com.sunny.flashsale.common.web;

import com.sunny.flashsale.common.error.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Auto-configuration instead of component scanning: services get this behaviour just by having
 * {@code common} on the classpath, and they never have to widen their own {@code @SpringBootApplication} scan.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonAutoConfiguration {

    /** Injected rather than calling Instant.now() directly, so time-dependent logic (hold expiry) is testable. */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler(Clock clock) {
        return new GlobalExceptionHandler(clock);
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        // Run first so every later filter (and, in phase 6, Spring Security) logs with the id already set.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
