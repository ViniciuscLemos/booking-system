package io.github.viniciusclemos.booking.domain;

import java.time.Duration;

/**
 * The business's own rules.
 *
 * @param minNotice   how early a client has to book (no "in 5 minutes")
 * @param maxAdvance  how far ahead the calendar is open
 * @param slotStep    the grid the offered times sit on (every 15 min, every 30...)
 */
public record BookingPolicy(Duration minNotice, Duration maxAdvance, Duration slotStep) {

    public static final BookingPolicy DEFAULT =
            new BookingPolicy(Duration.ofHours(2), Duration.ofDays(60), Duration.ofMinutes(15));

    public BookingPolicy {
        if (minNotice.isNegative() || maxAdvance.compareTo(minNotice) <= 0) {
            throw new IllegalArgumentException("max advance has to be after the minimum notice");
        }
        if (slotStep.isNegative() || slotStep.isZero()) {
            throw new IllegalArgumentException("slot step must be positive");
        }
    }
}
