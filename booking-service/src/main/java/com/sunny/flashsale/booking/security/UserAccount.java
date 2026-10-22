package com.sunny.flashsale.booking.security;

public record UserAccount(String id, String username, String passwordHash, Role role) {
}
