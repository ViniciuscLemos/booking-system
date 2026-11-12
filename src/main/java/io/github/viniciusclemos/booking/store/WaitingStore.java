package io.github.viniciusclemos.booking.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** The waiting list: people who wanted a day that was full. */
@Repository
public class WaitingStore {

    /** One person waiting, with where they are in the line for that day. */
    public record Waiting(long id, long serviceId, String service, Long professionalId, String professional,
                          LocalDate day, String clientName, String clientPhone, OffsetDateTime createdAt) {
    }

    private final JdbcClient db;

    public WaitingStore(JdbcClient db) {
        this.db = db;
    }

    /**
     * Puts someone on the list and returns their place in the line for that day. Asking
     * again with the same phone keeps the place they already had.
     */
    public int add(long serviceId, Long professionalId, LocalDate day, String name, String phone) {
        db.sql("""
                        INSERT INTO waiting (service_id, professional_id, day, client_name, client_phone)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT (day, service_id, client_phone) DO NOTHING""")
                .params(serviceId, professionalId, day, name, phone).update();
        return db.sql("""
                        SELECT count(*) FROM waiting w
                        WHERE w.day = ? AND w.created_at <= (SELECT created_at FROM waiting
                                                            WHERE day = ? AND service_id = ? AND client_phone = ?)""")
                .params(day, day, serviceId, phone).query(Integer.class).single();
    }

    public List<Waiting> on(LocalDate day) {
        return db.sql("""
                        SELECT w.id, w.service_id, s.name AS service, w.professional_id, p.name AS professional,
                               w.day, w.client_name, w.client_phone, w.created_at
                        FROM waiting w JOIN service s ON s.id = w.service_id
                        LEFT JOIN professional p ON p.id = w.professional_id
                        WHERE w.day = ? ORDER BY w.created_at, w.id""")
                .param(day).query(Waiting.class).list();
    }

    public boolean remove(long id) {
        return db.sql("DELETE FROM waiting WHERE id = ?").param(id).update() > 0;
    }
}
