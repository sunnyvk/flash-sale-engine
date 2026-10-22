package com.sunny.flashsale.booking.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock UserRepository users;

    private AdminBootstrap bootstrap(String username, String password) {
        return new AdminBootstrap(users, new BCryptPasswordEncoder(4), username, password);
    }

    @Test
    @DisplayName("creates the ADMIN (lower-cased) when both variables are set")
    void createsAdmin() {
        when(users.insertIfAbsent(eq("root"), anyString(), eq(Role.ADMIN))).thenReturn(true);

        bootstrap("Root", "a-long-admin-password").run(null);

        verify(users).insertIfAbsent(eq("root"), anyString(), eq(Role.ADMIN));
    }

    @Test
    @DisplayName("does nothing when the variables are not set")
    void skipsWhenUnset() {
        bootstrap("", "").run(null);
        bootstrap("root", "").run(null);
        bootstrap("", "pw").run(null);

        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("an existing account is left alone: a restart never resets its password")
    void existingAccountIsNotTouched() {
        when(users.insertIfAbsent(eq("root"), anyString(), any())).thenReturn(false);

        bootstrap("root", "a-long-admin-password").run(null);

        verify(users, never()).findByUsername(anyString());
    }
}
