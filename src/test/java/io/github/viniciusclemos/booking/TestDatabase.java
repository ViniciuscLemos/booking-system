package io.github.viniciusclemos.booking;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A real Postgres for the tests (downloaded once by Maven, no Docker needed), and
 * a clock stopped on Monday 02/11/2026 at 07:00 so the dates in the tests don't rot.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 7, 0);

    private static EmbeddedPostgres postgres;

    static synchronized EmbeddedPostgres postgres() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return postgres;
    }

    @Bean
    DataSource dataSource() {
        return postgres().getPostgresDatabase();
    }

    @Bean
    @Primary
    Clock testClock() {
        return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    }
}
