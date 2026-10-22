package com.sunny.flashsale.booking.security;

import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository users;
    @Mock TokenService tokens;
    @Mock LoginAttemptLimiter limiter;
    @Mock RefreshTokenService refreshTokens;
    @Mock TokenRevocationList revocations;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);   // cheap work factor: tests stay fast
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, tokens, limiter, refreshTokens, revocations);
    }

    @Test
    @DisplayName("register stores a BCrypt hash (never the password) with role USER, and lower-cases the username")
    void registerStoresHash() {
        when(users.insertIfAbsent(eq("alice"), anyString(), eq(Role.USER))).thenReturn(true);

        service.register("  Alice ", "correct-horse");

        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(users).insertIfAbsent(eq("alice"), hash.capture(), eq(Role.USER));
        assertThat(hash.getValue()).startsWith("$2").isNotEqualTo("correct-horse");
        assertThat(encoder.matches("correct-horse", hash.getValue())).isTrue();
    }

    @Test
    @DisplayName("register with a taken username is a 409")
    void registerDuplicate() {
        when(users.insertIfAbsent(eq("alice"), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.register("alice", "correct-horse"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo(AuthService.USERNAME_TAKEN);
                });
    }

    @Test
    @DisplayName("login with the right password issues a token carrying the stored role")
    void loginSuccess() {
        when(users.findByUsername("root")).thenReturn(Optional.of(new UserAccount("id", "root", encoder.encode("s3cret-pass"), Role.ADMIN)));
        when(tokens.issue("root", Role.ADMIN)).thenReturn(new TokenService.IssuedToken("jwt", 900));
        when(refreshTokens.startFamily("root")).thenReturn("refresh-1");

        AuthService.Session session = service.login("Root", "s3cret-pass", "10.0.0.1");

        assertThat(session.access().value()).isEqualTo("jwt");
        assertThat(session.refreshToken()).isEqualTo("refresh-1");
        verify(limiter).beforeAttempt("root", "10.0.0.1");
        verify(limiter).onSuccess("root", "10.0.0.1");
    }

    @Test
    @DisplayName("wrong password and unknown user give the SAME 401, so usernames cannot be enumerated")
    void loginFailuresLookIdentical() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(new UserAccount("id", "alice", encoder.encode("right-password"), Role.USER)));
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        ApiException wrongPassword = catchApi(() -> service.login("alice", "wrong-password", "10.0.0.1"));
        ApiException unknownUser = catchApi(() -> service.login("ghost", "whatever-pass", "10.0.0.1"));

        assertThat(wrongPassword.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getCode()).isEqualTo(wrongPassword.getCode()).isEqualTo(AuthService.INVALID_CREDENTIALS);
        assertThat(unknownUser.getMessage()).isEqualTo(wrongPassword.getMessage());
        verify(tokens, never()).issue(anyString(), any());
        verify(refreshTokens, never()).startFamily(anyString());
        verify(limiter, never()).onSuccess(anyString(), anyString());   // failures keep counting against the limit
    }

    @Test
    @DisplayName("rate limited: the 429 is thrown BEFORE the user is even looked up, so no BCrypt time is spent on an attacker")
    void rateLimitedLoginDoesNoWork() {
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, LoginAttemptLimiter.RATE_LIMITED, "slow down"))
                .when(limiter).beforeAttempt("alice", "10.0.0.1");

        assertThatThrownBy(() -> service.login("alice", "whatever-pass", "10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(users, tokens, refreshTokens);
    }

    @Test
    @DisplayName("refresh: new access token uses the role currently in the database, plus the rotated refresh token")
    void refreshRereadsRole() {
        when(refreshTokens.rotate("old")).thenReturn(new RefreshTokenService.Rotated("alice", "new"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(new UserAccount("id", "alice", "hash", Role.ADMIN)));
        when(tokens.issue("alice", Role.ADMIN)).thenReturn(new TokenService.IssuedToken("jwt2", 900));

        AuthService.Session session = service.refresh("old");

        assertThat(session.access().value()).isEqualTo("jwt2");
        assertThat(session.refreshToken()).isEqualTo("new");
    }

    @Test
    @DisplayName("refresh for a user that no longer exists is a 401")
    void refreshForDeletedUser() {
        when(refreshTokens.rotate("old")).thenReturn(new RefreshTokenService.Rotated("ghost", "new"));
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("old"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("logout revokes the access token's jti, and the refresh family only when a refresh token is given")
    void logout() {
        Instant exp = Instant.parse("2026-10-19T10:15:00Z");

        service.logout("jti-1", exp, "refresh-1");
        service.logout("jti-2", exp, null);
        service.logout("jti-3", exp, "  ");

        verify(revocations).revoke("jti-1", exp);
        verify(revocations).revoke("jti-2", exp);
        verify(revocations).revoke("jti-3", exp);
        verify(refreshTokens).revokeFamilyOf("refresh-1");
        verify(refreshTokens, org.mockito.Mockito.times(1)).revokeFamilyOf(anyString());
    }

    private static ApiException catchApi(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected ApiException");
    }
}
