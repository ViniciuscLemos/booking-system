package io.github.viniciusclemos.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(BookingSettings.class)
public class AppConfig {

    // a bean so the tests can freeze "now"
    @Bean
    Clock clock(BookingSettings settings) {
        return Clock.system(settings.zone());
    }
}
