package io.github.viniciusclemos.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Runs the demo (see DemoData) on an embedded Postgres, so you can click around without
 * installing anything: {@code mvn spring-boot:test-run}. Everything is gone when it stops.
 */
public class DemoApplication {

    public static void main(String[] args) {
        String[] demo = Stream.concat(Arrays.stream(args), Stream.of("--booking.demo=true")).toArray(String[]::new);
        SpringApplication.from(BookingApplication::main).with(Demo.class).run(demo);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Demo {

        @Bean
        DataSource dataSource() {
            return TestDatabase.postgres().getPostgresDatabase();
        }
    }
}
