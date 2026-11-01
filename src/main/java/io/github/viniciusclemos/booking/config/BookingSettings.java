package io.github.viniciusclemos.booking.config;

import io.github.viniciusclemos.booking.domain.BookingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties("booking")
public record BookingSettings(ZoneId zone, Duration minNotice, Duration maxAdvance, Duration slotStep, String businessName) {

    public BookingPolicy policy() {
        return new BookingPolicy(minNotice, maxAdvance, slotStep);
    }
}
