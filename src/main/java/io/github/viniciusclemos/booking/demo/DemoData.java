package io.github.viniciusclemos.booking.demo;

import io.github.viniciusclemos.booking.auth.StaffStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * booking.demo=true turns an install into the public demo: a barbershop already set up,
 * a staff login anyone can use (shown on the login page) and bookings over the next two
 * weeks, topped up every night so the calendar never runs empty.
 */
@Component
@ConditionalOnProperty("booking.demo")
public class DemoData implements ApplicationRunner {

    public static final String EMAIL = "rui@northside.test";
    public static final String PASSWORD = "northside-demo";

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private static final String[] NAMES = {"Lucas Almeida", "Pedro Rocha", "Gabriel Costa", "Mateus Oliveira",
            "Rafael Souza", "Thiago Martins", "Bruno Ferreira", "Felipe Lima", "Caio Ribeiro", "Diego Santos"};

    private final JdbcClient db;
    private final StaffStore staff;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final Random random = new Random(7);

    public DemoData(JdbcClient db, StaffStore staff, PasswordEncoder encoder, Clock clock) {
        this.db = db;
        this.staff = staff;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (staff.isEmpty()) staff.create("Rui", EMAIL, encoder.encode(PASSWORD));
        if (db.sql("SELECT count(*) FROM service").query(Integer.class).single() == 0) {
            for (int day = 1; day <= 5; day++) {
                hours(day, "09:00", "12:00");
                hours(day, "13:00", "19:00");
            }
            hours(6, "09:00", "14:00");
            service("Haircut", "Scissors or clipper, washed and styled.", 30, 10, 4500);
            service("Beard trim", "Shaped with a razor and a hot towel.", 20, 5, 3000);
            service("Haircut and beard", "Both, in one go.", 50, 10, 7000);
            service("Kids cut", "Up to 12 years old.", 25, 5, 3500);
            service("Hair coloring", "Covers grey or a full color change.", 60, 15, 9000);
            log.info("Demo barbershop created");
        }
        // three barbers: the one the migration made, renamed, and two more
        if (db.sql("SELECT count(*) FROM professional").query(Integer.class).single() == 1) {
            db.sql("UPDATE professional SET name = 'Rui' WHERE name = 'Me'").update();
            db.sql("INSERT INTO professional (name) VALUES ('Bia'), ('Caio')").update();
            // Caio is new and doesn't do coloring yet; the other two do everything
            db.sql("""
                    INSERT INTO professional_service (professional_id, service_id)
                    SELECT p.id, s.id FROM professional p, service s WHERE p.name = 'Caio' AND s.name <> 'Hair coloring'""")
                    .update();
        }
        topUp();
    }

    private record Offer(long id, int durationMin, int bufferMin) {
    }

    /** Every night, so the next two weeks always have some bookings. */
    @Scheduled(cron = "0 0 4 * * *", zone = "${booking.zone}")
    public void topUp() {
        // whatever services exist now, the staff may have changed them since the start
        List<Offer> offers = db.sql("SELECT id, duration_min, buffer_min FROM service WHERE active ORDER BY id")
                .query(Offer.class).list();
        List<Long> barbers = db.sql("SELECT id FROM professional WHERE active ORDER BY id").query(Long.class).list();
        if (offers.isEmpty() || barbers.isEmpty()) return;
        LocalDate today = LocalDate.now(clock);
        for (int d = 0; d < 14; d++) {
            LocalDate date = today.plusDays(d);
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            int already = db.sql("SELECT count(*) FROM booking WHERE starts >= ? AND starts < ?")
                    .params(date.atStartOfDay(), date.plusDays(1).atStartOfDay()).query(Integer.class).single();
            if (already >= 3 * barbers.size()) continue;
            int lastHour = date.getDayOfWeek() == DayOfWeek.SATURDAY ? 13 : 18;
            for (int i = 0; i < 6 * barbers.size(); i++) {
                LocalDateTime start = date.atTime(9 + random.nextInt(lastHour - 9), random.nextBoolean() ? 0 : 30);
                Offer offer = offers.get(random.nextInt(offers.size()));
                LocalDateTime end = start.plusMinutes(offer.durationMin());
                LocalDateTime blocked = end.plusMinutes(offer.bufferMin());
                // stays out of lunch, closing time and the past, like a real booking would
                if (start.isBefore(LocalDateTime.now(clock))) continue;
                if (start.getHour() < 12 && end.isAfter(date.atTime(12, 0))) continue;
                if (start.getHour() == 12 || end.isAfter(date.atTime(lastHour + 1, 0))) continue;
                long barber = barbers.get(random.nextInt(barbers.size()));
                // only someone who does that service
                boolean does = db.sql("""
                                SELECT NOT EXISTS (SELECT 1 FROM professional_service WHERE professional_id = ?)
                                    OR EXISTS (SELECT 1 FROM professional_service WHERE professional_id = ? AND service_id = ?)""")
                        .params(barber, barber, offer.id()).query(Boolean.class).single();
                if (!does) continue;
                db.sql("""
                                INSERT INTO booking (service_id, professional_id, client_name, client_phone, starts, ends,
                                                     blocked_until)
                                SELECT ?, ?, ?, ?, ?, ?, ?
                                WHERE NOT EXISTS (SELECT 1 FROM booking
                                                  WHERE status = 'CONFIRMED' AND professional_id = ?
                                                    AND tsrange(starts, blocked_until) && tsrange(?, ?))""")
                        .params(offer.id(), barber, NAMES[random.nextInt(NAMES.length)],
                                "119" + (10000000 + random.nextInt(89999999)), start, end, blocked, barber, start, blocked)
                        .update();
            }
        }
    }

    private void hours(int day, String starts, String ends) {
        db.sql("INSERT INTO working_hours (day_of_week, starts, ends) VALUES (?, ?::time, ?::time)")
                .params(day, starts, ends).update();
    }

    private void service(String name, String description, int minutes, int buffer, long cents) {
        db.sql("INSERT INTO service (name, description, duration_min, buffer_min, price_cents) VALUES (?, ?, ?, ?, ?)")
                .params(name, description, minutes, buffer, cents).update();
    }
}
