package com.sunny.flashsale.booking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the production-readiness rules from the project brief without needing a database:
 * secrets come from the environment with no default, and Hibernate may not touch the schema.
 */
class BookingConfigTest {

    private final PropertySource<?> yaml = load();

    private static PropertySource<?> load() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            return sources.get(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String prop(String key) {
        return String.valueOf(yaml.getProperty(key));
    }

    @Test
    @DisplayName("DB credentials are bare env placeholders: no hardcoded value, no fallback default")
    void dbSecretsHaveNoDefault() {
        assertThat(prop("spring.datasource.username")).isEqualTo("${BOOKING_DB_USER}");
        assertThat(prop("spring.datasource.password")).isEqualTo("${BOOKING_DB_PASSWORD}");
    }

    @Test
    @DisplayName("the JWT signing secret is a bare env placeholder: no default, so a forgotten variable stops startup")
    void jwtSecretHasNoDefault() {
        assertThat(prop("jwt.secret")).isEqualTo("${JWT_SECRET}");
        assertThat(prop("auth.bootstrap-admin.password")).isEqualTo("${ADMIN_PASSWORD:}");   // empty default = no admin is created
    }

    @Test
    @DisplayName("Flyway owns the schema: Hibernate is validate-only")
    void hibernateValidatesOnly() {
        assertThat(prop("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(prop("spring.jpa.open-in-view")).isEqualTo("false");
    }

    @Test
    @DisplayName("service listens on its own default port and logs structured JSON")
    void portAndLogging() {
        assertThat(prop("server.port")).isEqualTo("${SERVER_PORT:8081}");
        assertThat(prop("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(prop("spring.application.name")).isEqualTo("booking-service");
    }
}
