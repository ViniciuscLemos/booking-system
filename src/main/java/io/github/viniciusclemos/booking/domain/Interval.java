package io.github.viniciusclemos.booking.domain;

import java.time.LocalDateTime;

/**
 * A real moment on the calendar, [start, end). Half-open on purpose: a booking
 * that ends at 10:00 and another that starts at 10:00 don't clash.
 */
public record Interval(LocalDateTime start, LocalDateTime end) {

    public Interval {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end: " + start + " - " + end);
        }
    }

    public boolean overlaps(Interval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean contains(Interval other) {
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }
}
