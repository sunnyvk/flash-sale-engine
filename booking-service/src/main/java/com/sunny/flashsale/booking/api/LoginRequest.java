package com.sunny.flashsale.booking.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 72) String password) {
}
