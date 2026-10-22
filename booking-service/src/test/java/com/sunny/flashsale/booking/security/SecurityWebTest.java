package com.sunny.flashsale.booking.security;

import com.sunny.flashsale.booking.api.AuthController;
import com.sunny.flashsale.booking.api.BookingController;
import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.idempotency.BookingResult;
import com.sunny.flashsale.booking.idempotency.IdempotentBookingService;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.GlobalExceptionHandler;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real security filter chain, real JWT decoding, mocked services: proves who gets in, who gets 401 and who gets 403.
 * No database needed.
 */
@WebMvcTest(controllers = {BookingController.class, AuthController.class})
@Import({SecurityConfig.class, SecurityErrorHandler.class, TokenService.class, SecurityWebTest.Beans.class})
@TestPropertySource(properties = {
        "jwt.secret=web-test-secret-web-test-secret-0123456789",
        "jwt.issuer=https://test-issuer.local"
})
class SecurityWebTest {

    private static final String SECRET = "web-test-secret-web-test-secret-0123456789";
    private static final String BOOKING_ID = "88888888-8888-8888-8888-888888888888";

    @TestConfiguration
    @EnableConfigurationProperties(JwtProperties.class)
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired JwtEncoder encoder;
    @MockitoBean BookingService bookings;
    @MockitoBean IdempotentBookingService idempotent;
    @MockitoBean AuthService authService;
    @MockitoBean TokenRevocationList revocations;   // SecurityConfig's decoder consults it; a mock says "not revoked"

    private String bearer(String username, Role role) {
        return "Bearer " + tokens.issue(username, role).value();
    }

    private static Booking booking(String owner) {
        return new Booking(BOOKING_ID, owner, 1L, 42L, new BigDecimal("1500.00"), "k1");
    }

    private String tokenWith(JwtEncoder enc, String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("alice").issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("roles", List.of("USER")).build();
        return "Bearer " + enc.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    // ------------------------------------------------------------------ authentication: who is rejected

    @Test
    @DisplayName("no token: 401 in the standard error format, with a WWW-Authenticate challenge")
    void noToken() throws Exception {
        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("garbage token: 401")
    void garbageToken() throws Exception {
        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("expired token: 401")
    void expiredToken() throws Exception {
        Instant now = Instant.now();
        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID)
                        .header("Authorization", tokenWith(encoder, "https://test-issuer.local", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token from a different issuer: 401")
    void wrongIssuer() throws Exception {
        Instant now = Instant.now();
        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID)
                        .header("Authorization", tokenWith(encoder, "https://someone-else.local", now, now.plus(Duration.ofMinutes(5)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token signed with a different key (forged): 401")
    void forgedSignature() throws Exception {
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec("attacker-secret-attacker-secret-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID)
                        .header("Authorization", tokenWith(attacker, "https://test-issuer.local", now, now.plus(Duration.ofMinutes(5)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unsigned token with alg=none: 401 (the algorithm is pinned, so 'none' is never accepted)")
    void algNone() throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().plusSeconds(300).getEpochSecond();
        String payload = b64.encodeToString(("{\"iss\":\"https://test-issuer.local\",\"sub\":\"alice\",\"roles\":[\"ADMIN\"],\"exp\":" + exp + "}")
                .getBytes(StandardCharsets.UTF_8));

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).header("Authorization", "Bearer " + header + "." + payload + "."))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ authorization: who may do what

    @Test
    @DisplayName("valid USER token: can read their own booking")
    void userReadsOwn() throws Exception {
        when(bookings.get(BOOKING_ID)).thenReturn(booking("alice"));

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).header("Authorization", bearer("alice", Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(BOOKING_ID));
    }

    @Test
    @DisplayName("valid USER token: somebody else's booking is 404")
    void userCannotReadOthers() throws Exception {
        when(bookings.get(BOOKING_ID)).thenReturn(booking("alice"));

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).header("Authorization", bearer("bob", Role.USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADMIN token: can read anyone's booking")
    void adminReadsAny() throws Exception {
        when(bookings.get(BOOKING_ID)).thenReturn(booking("alice"));

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).header("Authorization", bearer("root", Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER on the ADMIN-only confirm route: 403 in the standard error format")
    void userCannotConfirm() throws Exception {
        mvc.perform(post("/api/v1/bookings/" + BOOKING_ID + "/confirm").header("Authorization", bearer("alice", Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("ADMIN on the confirm route: allowed")
    void adminCanConfirm() throws Exception {
        Booking confirmed = booking("alice");
        confirmed.markConfirmed();
        when(bookings.confirm(BOOKING_ID)).thenReturn(confirmed);

        mvc.perform(post("/api/v1/bookings/" + BOOKING_ID + "/confirm").header("Authorization", bearer("root", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("booking uses the token's subject as the user, whatever the body claims")
    void bookingUsesTokenSubject() throws Exception {
        when(idempotent.book("alice", 1L, "A-001", "k1")).thenReturn(new BookingResult(booking("alice"), false));

        mvc.perform(post("/api/v1/bookings").header("Authorization", bearer("alice", Role.USER)).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatNumber\":\"A-001\",\"userId\":\"mallory\"}"))
                .andExpect(status().isCreated());

        verify(idempotent).book("alice", 1L, "A-001", "k1");
    }

    @Test
    @DisplayName("booking without a token: 401, nothing reaches the service")
    void bookingNeedsToken() throws Exception {
        mvc.perform(post("/api/v1/bookings").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ public routes

    @Test
    @DisplayName("availability is public")
    void availabilityIsPublic() throws Exception {
        when(bookings.availability(1L)).thenReturn(new BookingService.Availability(1L, 90, 8, 2));

        mvc.perform(get("/api/v1/events/1/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(90));
    }

    @Test
    @DisplayName("register and login are public; a bad body is a 400")
    void authRoutesArePublic() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("login returns a Bearer token response")
    void loginReturnsToken() throws Exception {
        when(authService.login(eq("alice"), eq("correct-horse"), anyString()))
                .thenReturn(new AuthService.Session(new TokenService.IssuedToken("the.jwt.value", 900), "the-refresh-token"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("the.jwt.value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.refreshToken").value("the-refresh-token"));
    }

    @Test
    @DisplayName("refresh is public (the access token has expired by then) and returns a new pair")
    void refreshIsPublic() throws Exception {
        when(authService.refresh("old-refresh"))
                .thenReturn(new AuthService.Session(new TokenService.IssuedToken("new.jwt", 900), "new-refresh"));

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("logout needs a token (401 without), and passes that token's jti and expiry plus the refresh token to the service")
    void logoutRevokesTheCallersToken() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer("alice", Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r-1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer("alice", Role.USER)))   // no body at all
                .andExpect(status().isNoContent());

        verify(authService).logout(anyString(), any(Instant.class), eq("r-1"));
        verify(authService).logout(anyString(), any(Instant.class), isNull());
    }

    @Test
    @DisplayName("a token whose jti is on the revocation list is rejected with 401, even though signature and expiry are fine")
    void revokedTokenIsRejected() throws Exception {
        when(revocations.isRevoked(anyString())).thenReturn(true);

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).header("Authorization", bearer("alice", Role.USER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("any route not listed in the rules is denied by default")
    void unknownRoutesAreDenied() throws Exception {
        mvc.perform(get("/api/v1/admin/secret")).andExpect(status().isUnauthorized());
    }
}
