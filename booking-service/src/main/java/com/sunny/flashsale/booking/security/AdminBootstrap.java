package com.sunny.flashsale.booking.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN from environment variables, because the API cannot (self-registration is USER only).
 * Does nothing when the variables are unset or the account already exists, so it is safe on every restart.
 * It never changes an existing account's password: rotating that is a deliberate act, not a side effect of a restart.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          @Value("${auth.bootstrap-admin.username:}") String username,
                          @Value("${auth.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) {
            log.info("No bootstrap admin configured (ADMIN_USERNAME / ADMIN_PASSWORD unset)");
            return;
        }
        String name = AuthService.normalize(username);
        if (users.insertIfAbsent(name, encoder.encode(password), Role.ADMIN)) {
            log.warn("Bootstrap admin created: username={}", name);
        } else {
            log.info("Bootstrap admin already exists: username={}", name);
        }
    }
}
