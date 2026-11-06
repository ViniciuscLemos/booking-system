package io.github.viniciusclemos.booking.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Who takes this service: whoever has it listed, or has nothing listed (does everything). */
    public List<Professional> doing(long serviceId) {
        return db.sql("""
                        SELECT p.id, p.name, p.active FROM professional p
                        WHERE p.active AND (NOT EXISTS (SELECT 1 FROM professional_service x WHERE x.professional_id = p.id)
                                            OR EXISTS (SELECT 1 FROM professional_service x
                                                       WHERE x.professional_id = p.id AND x.service_id = ?))
                        ORDER BY p.id""")
                .param(serviceId).query(Professional.class).list();
    }

    /** The services listed for each person; someone missing from the map does all of them. */
    public Map<Long, List<Long>> servicesOf() {
        Map<Long, List<Long>> map = new LinkedHashMap<>();
        db.sql("SELECT professional_id, service_id FROM professional_service ORDER BY professional_id, service_id")
                .query(rs -> {
                    map.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
                });
        return map;
    }

    /** An empty list means "does everything". */
    @org.springframework.transaction.annotation.Transactional
    public void setServices(long professionalId, List<Long> serviceIds) {
        db.sql("DELETE FROM professional_service WHERE professional_id = ?").param(professionalId).update();
        for (long id : serviceIds) {
            db.sql("INSERT INTO professional_service (professional_id, service_id) VALUES (?, ?)")
                    .params(professionalId, id).update();
        }
    }

    public long create(String name) {
        return db.sql("INSERT INTO professional (name) VALUES (?) RETURNING id").param(name).query(Long.class).single();
    }

    public boolean update(long id, String name, boolean active) {
        return db.sql("UPDATE professional SET name = ?, active = ? WHERE id = ?").params(name, active, id).update() == 1;
    }
}
