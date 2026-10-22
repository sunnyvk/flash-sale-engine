package com.sunny.flashsale.booking.api;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, String refreshToken) {
}
