package com.sunny.flashsale.booking.security;

/** Mirrors the CHECK constraint on users.role. Spring Security adds the "ROLE_" prefix when it builds authorities. */
public enum Role {
    USER,
    ADMIN
}
