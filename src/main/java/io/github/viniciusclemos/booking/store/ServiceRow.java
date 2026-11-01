package io.github.viniciusclemos.booking.store;

import io.github.viniciusclemos.booking.domain.ServiceOffering;

import java.time.Duration;

public record ServiceRow(long id, String name, String description, int durationMin, int bufferMin, long priceCents,
                         boolean active) {

    public ServiceOffering toOffering() {
        return new ServiceOffering(name, Duration.ofMinutes(durationMin), Duration.ofMinutes(bufferMin), priceCents);
    }
}
