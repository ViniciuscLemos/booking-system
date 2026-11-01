package io.github.viniciusclemos.booking.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ServiceStore {

    private final JdbcClient db;

    public ServiceStore(JdbcClient db) {
        this.db = db;
    }

    public List<ServiceRow> findActive() {
        return db.sql("SELECT * FROM service WHERE active ORDER BY price_cents, name")
                .query(ServiceRow.class).list();
    }

    public List<ServiceRow> findAll() {
        return db.sql("SELECT * FROM service ORDER BY active DESC, name").query(ServiceRow.class).list();
    }

    public Optional<ServiceRow> findById(long id) {
        return db.sql("SELECT * FROM service WHERE id = ?").param(id).query(ServiceRow.class).optional();
    }

    public long create(String name, String description, int durationMin, int bufferMin, long priceCents) {
        return db.sql("""
                        INSERT INTO service (name, description, duration_min, buffer_min, price_cents)
                        VALUES (?, ?, ?, ?, ?) RETURNING id""")
                .params(name, description, durationMin, bufferMin, priceCents)
                .query(Long.class).single();
    }

    public boolean update(long id, String name, String description, int durationMin, int bufferMin, long priceCents,
                          boolean active) {
        return db.sql("""
                        UPDATE service SET name = ?, description = ?, duration_min = ?, buffer_min = ?,
                               price_cents = ?, active = ?
                        WHERE id = ?""")
                .params(name, description, durationMin, bufferMin, priceCents, active, id)
                .update() == 1;
    }
}
