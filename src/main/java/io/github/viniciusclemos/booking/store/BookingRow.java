package io.github.viniciusclemos.booking.store;

import io.github.viniciusclemos.booking.domain.Booking;
import io.github.viniciusclemos.booking.domain.ServiceOffering;

import java.time.Duration;
import java.time.LocalDateTime;

public record BookingRow(long id, long serviceId, String serviceName, String clientName, String clientPhone,
                         LocalDateTime starts, LocalDateTime ends, LocalDateTime blockedUntil, String status,
                         LocalDateTime cancelledAt, String cancelledBy) {

    public boolean confirmed() {
        return "CONFIRMED".equals(status);
    }

    /** For the rules, built from the stored times and not from the service as it is today. */
    public Booking toBooking() {
        ServiceOffering asBooked = new ServiceOffering(serviceName, Duration.between(starts, ends),
                Duration.between(ends, blockedUntil), 0);
        return new Booking(asBooked, starts);
    }
}
