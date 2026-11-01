package io.github.viniciusclemos.booking.domain;

import java.time.LocalTime;

/** A stretch of the day, like 09:00-12:00. The end is not included. */
public record TimeRange(LocalTime start, LocalTime end) {

    public TimeRange {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end are required");
        }
        // a shift that crosses midnight would need two ranges; not supported for now
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end: " + start + "-" + end);
        }
    }

    public static TimeRange of(String start, String end) {
        return new TimeRange(LocalTime.parse(start), LocalTime.parse(end));
    }

    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
