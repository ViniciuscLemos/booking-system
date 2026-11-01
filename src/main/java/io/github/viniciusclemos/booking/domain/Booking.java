package io.github.viniciusclemos.booking.domain;

import java.time.LocalDateTime;

/** An appointment already on the calendar. */
public record Booking(ServiceOffering service, LocalDateTime start) {

    /** When the client is in the chair. */
    public Interval appointment() {
        return new Interval(start, start.plus(service.duration()));
    }

    /** What nobody else can take: the appointment plus the service's buffer. */
    public Interval blocked() {
        return new Interval(start, start.plus(service.duration()).plus(service.buffer()));
    }
}
