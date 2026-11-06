package io.github.viniciusclemos.booking.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProfessionalStore {

    public record Professional(long id, String name, boolean active) {
    }

    private final JdbcClient db;

    public ProfessionalStore(JdbcClient db) {
        this.db = db;
    }

    public List<Professional> active() {
        return db.sql("SELECT id, name, active FROM professional WHERE active ORDER BY id").query(Professional.class).list();
    }

    public List<Professional> all() {
        return db.sql("SELECT id, name, active FROM professional ORDER BY active DESC, id").query(Professional.class).list();
    }

    public Optional<Professional> find(long id) {
        return db.sql("SELECT id, name, active FROM professional WHERE id = ?").param(id).query(Professional.class).optional();
    }

    public long create(String name) {
        return db.sql("INSERT INTO professional (name) VALUES (?) RETURNING id").param(name).query(Long.class).single();
    }

    public boolean update(long id, String name, boolean active) {
        return db.sql("UPDATE professional SET name = ?, active = ? WHERE id = ?").params(name, active, id).update() == 1;
    }
}
