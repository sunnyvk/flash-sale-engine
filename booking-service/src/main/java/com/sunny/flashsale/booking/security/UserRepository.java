package com.sunny.flashsale.booking.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "select id, username, password_hash, role from users where username = ?",
                    (rs, i) -> new UserAccount(rs.getString("id"), rs.getString("username"),
                            rs.getString("password_hash"), Role.valueOf(rs.getString("role"))),
                    username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** The unique key decides, so two simultaneous registrations of one name cannot both win. */
    public boolean insertIfAbsent(String username, String passwordHash, Role role) {
        try {
            jdbc.update("insert into users (id, username, password_hash, role) values (?, ?, ?, ?)",
                    UUID.randomUUID().toString(), username, passwordHash, role.name());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
