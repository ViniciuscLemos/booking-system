package io.github.viniciusclemos.booking;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * Runs the app on an embedded Postgres with a barbershop already set up, so you can
 * click around without installing anything: {@code mvn spring-boot:test-run}.
 * Everything is gone when it stops.
 */
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.from(BookingApplication::main).with(Demo.class).run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Demo {

        @Bean
        DataSource dataSource() {
            return TestDatabase.postgres().getPostgresDatabase();
        }

        @Bean
        ApplicationRunner seed(JdbcClient db, Clock clock) {
            return args -> {
                if (db.sql("SELECT count(*) FROM service").query(Integer.class).single() > 0) return;

                for (int day = 1; day <= 5; day++) {
                    hours(db, day, "09:00", "12:00");
                    hours(db, day, "13:00", "19:00");
                }
                hours(db, 6, "09:00", "14:00");

                service(db, "Haircut", "Scissors or clipper, washed and styled.", 30, 10, 4500);
                service(db, "Beard trim", "Shaped with a razor and a hot towel.", 20, 5, 3000);
                service(db, "Haircut and beard", "Both, in one go.", 50, 10, 7000);
                service(db, "Kids cut", "Up to 12 years old.", 25, 5, 3500);
                service(db, "Hair coloring", "Covers grey or a full color change.", 60, 15, 9000);

                // some bookings already taken over the next days so the calendar looks lived in
                Random random = new Random(7);
                LocalDate today = LocalDate.now(clock);
                for (int d = 0; d < 14; d++) {
                    LocalDate date = today.plusDays(d);
                    if (date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
                    int lastHour = date.getDayOfWeek() == DayOfWeek.SATURDAY ? 13 : 18;
                    for (int i = 0; i < 6; i++) {
                        int hour = 9 + random.nextInt(lastHour - 9);
                        if (hour == 12) continue;
                        LocalDateTime start = date.atTime(hour, random.nextBoolean() ? 0 : 30);
                        db.sql("""
                                        INSERT INTO booking (service_id, client_name, client_phone, starts, ends, blocked_until)
                                        SELECT 1, 'Walk-in', '11900000000', ?, ?, ?
                                        WHERE NOT EXISTS (SELECT 1 FROM booking WHERE tsrange(starts, blocked_until) && tsrange(?, ?))""")
                                .params(start, start.plusMinutes(30), start.plusMinutes(40), start, start.plusMinutes(40))
                                .update();
                    }
                }
            };
        }

        private static void hours(JdbcClient db, int day, String starts, String ends) {
            db.sql("INSERT INTO working_hours (day_of_week, starts, ends) VALUES (?, ?::time, ?::time)")
                    .params(day, starts, ends).update();
        }

        private static void service(JdbcClient db, String name, String description, int minutes, int buffer, long cents) {
            db.sql("INSERT INTO service (name, description, duration_min, buffer_min, price_cents) VALUES (?, ?, ?, ?, ?)")
                    .params(name, description, minutes, buffer, cents).update();
        }
    }
}
