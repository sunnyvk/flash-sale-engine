package com.sunny.flashsale.booking.security;

import com.sunny.flashsale.booking.security.RefreshTokenRepository.Row;
import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);

    @Mock RefreshTokenRepository repo;

    private final RefreshTokenService service() {
        return new RefreshTokenService(repo, new RefreshProperties(TTL), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Row row(Instant expiresAt, Instant usedAt, Instant revokedAt) {
        return new Row("id-1", "fam-1", "alice", expiresAt, usedAt, revokedAt);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.getCode()).isEqualTo(RefreshTokenService.INVALID_REFRESH_TOKEN);
        });
    }

    @Test
    @DisplayName("login: a 256-bit random token is returned, and only its SHA-256 hash is stored, with the configured expiry")
    void startFamilyStoresOnlyTheHash() {
        String raw = service().startFamily("alice");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repo).insert(anyString(), anyString(), eq("alice"), hash.capture(), eq(NOW.plus(TTL)));
        assertThat(raw).hasSize(43);                         // 32 bytes, base64url without padding
        assertThat(hash.getValue()).hasSize(64).isEqualTo(RefreshTokenService.hash(raw)).isNotEqualTo(raw);
    }

    @Test
    @DisplayName("two logins never produce the same token")
    void tokensAreUnique() {
        RefreshTokenService s = service();
        assertThat(s.startFamily("alice")).isNotEqualTo(s.startFamily("alice"));
    }

    @Test
    @DisplayName("valid token: spent and replaced by a new one in the SAME family")
    void rotates() {
        String raw = "old-token";
        when(repo.findByHash(RefreshTokenService.hash(raw))).thenReturn(Optional.of(row(NOW.plusSeconds(60), null, null)));
        when(repo.rotate(eq(RefreshTokenService.hash(raw)), anyString(), eq("fam-1"), eq("alice"), anyString(), eq(NOW.plus(TTL)), eq(NOW))).thenReturn(true);

        RefreshTokenService.Rotated rotated = service().rotate(raw);

        assertThat(rotated.username()).isEqualTo("alice");
        assertThat(rotated.newToken()).isNotEqualTo(raw).hasSize(43);
        verify(repo, never()).revokeFamily(anyString(), any());
    }

    @Test
    @DisplayName("an unknown token is a plain 401 and revokes nothing")
    void unknownToken() {
        when(repo.findByHash(anyString())).thenReturn(Optional.empty());

        assertInvalid(() -> service().rotate("nope"));
        verify(repo, never()).revokeFamily(anyString(), any());
    }

    @Test
    @DisplayName("an expired token is a 401")
    void expired() {
        when(repo.findByHash(anyString())).thenReturn(Optional.of(row(NOW, null, null)));

        assertInvalid(() -> service().rotate("t"));
        verify(repo, never()).rotate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an already-revoked token is a 401")
    void revoked() {
        when(repo.findByHash(anyString())).thenReturn(Optional.of(row(NOW.plusSeconds(60), null, NOW.minusSeconds(5))));

        assertInvalid(() -> service().rotate("t"));
    }

    @Test
    @DisplayName("REUSE of a spent token: 401 and the whole family is revoked (the thief and the real user both lose the session)")
    void reuseRevokesFamily() {
        when(repo.findByHash(anyString())).thenReturn(Optional.of(row(NOW.plusSeconds(60), NOW.minusSeconds(30), null)));

        assertInvalid(() -> service().rotate("spent"));
        verify(repo).revokeFamily("fam-1", NOW);
    }

    @Test
    @DisplayName("losing the race (rotate returns false) is treated as reuse: family revoked")
    void lostRaceIsReuse() {
        when(repo.findByHash(anyString())).thenReturn(Optional.of(row(NOW.plusSeconds(60), null, null)));
        when(repo.rotate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(false);

        assertInvalid(() -> service().rotate("t"));
        verify(repo).revokeFamily("fam-1", NOW);
    }

    @Test
    @DisplayName("logout: revokes the family of a known token, ignores an unknown one")
    void revokeFamilyOf() {
        when(repo.findByHash(RefreshTokenService.hash("known"))).thenReturn(Optional.of(row(NOW.plusSeconds(60), null, null)));
        when(repo.findByHash(RefreshTokenService.hash("unknown"))).thenReturn(Optional.empty());

        service().revokeFamilyOf("known");
        service().revokeFamilyOf("unknown");

        verify(repo).revokeFamily("fam-1", NOW);
        verify(repo, org.mockito.Mockito.times(1)).revokeFamily(anyString(), any());
    }
}
