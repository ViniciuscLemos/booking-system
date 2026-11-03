package io.github.viniciusclemos.booking.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class StaffStore {

    public record Staff(long id, String name, String email, String passwordHash) {
    }

    private final JdbcClient db;

    public StaffStore(JdbcClient db) {
        this.db = db;
    }

    public Optional<Staff> findByEmail(String email) {
        return db.sql("SELECT id, name, email, password_hash FROM staff WHERE lower(email) = lower(?)")
                .param(email.trim()).query(Staff.class).optional();
    }

    public boolean isEmpty() {
        return db.sql("SELECT NOT EXISTS (SELECT 1 FROM staff)").query(Boolean.class).single();
    }

    public long create(String name, String email, String passwordHash) {
        return db.sql("INSERT INTO staff (name, email, password_hash) VALUES (?, ?, ?) RETURNING id")
                .params(name.trim(), email.trim(), passwordHash).query(Long.class).single();
    }
}
