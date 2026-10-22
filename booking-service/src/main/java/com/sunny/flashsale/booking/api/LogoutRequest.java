package com.sunny.flashsale.booking.api;

import jakarta.validation.constraints.Size;

/** The refresh token is optional: send it to end the whole session, omit it to revoke only the current access token. */
public record LogoutRequest(@Size(max = 200) String refreshToken) {
}
