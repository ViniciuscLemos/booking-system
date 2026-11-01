package io.github.viniciusclemos.booking.domain;

import java.time.Duration;

/**
 * Something the business sells by time: a haircut, a consultation.
 *
 * @param buffer time blocked after each appointment (cleaning, notes). It may
 *               run past closing time, but the next client can't start inside it
 */
public record ServiceOffering(String name, Duration duration, Duration buffer, long priceCents) {

    public ServiceOffering {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        buffer = buffer == null ? Duration.ZERO : buffer;
        if (buffer.isNegative()) {
            throw new IllegalArgumentException("buffer can't be negative");
        }
        if (priceCents < 0) {
            throw new IllegalArgumentException("price can't be negative");
        }
    }

    public static ServiceOffering of(String name, int minutes, int bufferMinutes) {
        return new ServiceOffering(name, Duration.ofMinutes(minutes), Duration.ofMinutes(bufferMinutes), 0);
    }
}
