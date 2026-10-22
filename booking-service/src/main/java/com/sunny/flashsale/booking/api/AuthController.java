package com.sunny.flashsale.booking.api;

import com.sunny.flashsale.booking.security.AuthService;
import com.sunny.flashsale.booking.security.AuthService.Session;
import com.sunny.flashsale.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.username(), request.password());
    }

    /** The IP comes from the connection. Behind a proxy, enable forward-headers-strategy so this is the real client. */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return toResponse(authService.login(request.username(), request.password(), http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return toResponse(authService.refresh(request.refreshToken()));
    }

    /** Needs a valid access token: it is the one being revoked. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication auth, @Valid @RequestBody(required = false) LogoutRequest request) {
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid access token is required");
        }
        authService.logout(jwtAuth.getToken().getId(), jwtAuth.getToken().getExpiresAt(),
                request == null ? null : request.refreshToken());
    }

    private static TokenResponse toResponse(Session session) {
        return new TokenResponse(session.access().value(), "Bearer", session.access().expiresInSeconds(), session.refreshToken());
    }
}
