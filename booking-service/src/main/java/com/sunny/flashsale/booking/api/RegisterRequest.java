package com.sunny.flashsale.booking.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Password max is 72 because BCrypt only reads the first 72 bytes: a longer password would silently be truncated.
 * Usernames are restricted to a safe alphabet because they end up in log lines, Redis keys and Kafka keys.
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "letters, digits, '.', '_' and '-' only") String username,
        @NotBlank @Size(min = 8, max = 72) String password) {
}
