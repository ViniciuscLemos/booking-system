package io.github.viniciusclemos.booking.scheduling;

import io.github.viniciusclemos.booking.domain.Booking;
import io.github.viniciusclemos.booking.domain.Calendar;
import io.github.viniciusclemos.booking.domain.Interval;
import io.github.viniciusclemos.booking.domain.ServiceOffering;
import io.github.viniciusclemos.booking.domain.TimeRange;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides if a service can start at a given time. The list of free slots is built
 * on top of this same check, so what the client sees as free is exactly what
 * the booking accepts.
 */
public final class BookingRules {

    private BookingRules() {
    }

    /** Every reason the time is refused; empty means it can be booked. */
    public static List<Violation> check(Calendar calendar, ServiceOffering service, LocalDateTime start, LocalDateTime now) {
        List<Violation> found = new ArrayList<>();
        Booking wanted = new Booking(service, start);
        Interval appointment = wanted.appointment();

        if (start.isBefore(now)) {
            found.add(Violation.IN_THE_PAST);
        } else if (start.isBefore(now.plus(calendar.policy().minNotice()))) {
            found.add(Violation.TOO_SOON);
        } else if (start.isAfter(now.plus(calendar.policy().maxAdvance()))) {
            found.add(Violation.TOO_FAR_AHEAD);
        }

        Violation hours = checkHours(calendar, appointment);
        if (hours != null) found.add(hours);

        if (calendar.timeOff().stream().anyMatch(appointment::overlaps)) {
            found.add(Violation.TIME_OFF);
        }
        Interval blocked = wanted.blocked();
        if (calendar.bookings().stream().anyMatch(b -> b.blocked().overlaps(blocked))) {
            found.add(Violation.CLASHES_WITH_BOOKING);
        }
        return found;
    }

    public static boolean isFree(Calendar calendar, ServiceOffering service, LocalDateTime start, LocalDateTime now) {
        return check(calendar, service, start, now).isEmpty();
    }

    private static Violation checkHours(Calendar calendar, Interval appointment) {
        LocalDateTime start = appointment.start();
        List<TimeRange> ranges = calendar.schedule().rangesOn(start.toLocalDate());
        if (ranges.isEmpty()) return Violation.CLOSED_THAT_DAY;

        // a range ends before midnight, so anything that crosses it can't fit
        if (!appointment.end().toLocalDate().equals(start.toLocalDate())) {
            return Violation.OUTSIDE_WORKING_HOURS;
        }
        LocalTime from = start.toLocalTime();
        LocalTime to = appointment.end().toLocalTime();

        for (TimeRange range : ranges) {
            if (!from.isBefore(range.start()) && !to.isAfter(range.end())) {
                return onGrid(range, from, calendar.policy().slotStep()) ? null : Violation.OFF_GRID;
            }
        }
        // starts inside working hours but doesn't fit: it hits the gap between two ranges
        // (the break) or the closing time
        boolean startsInside = ranges.stream()
                .anyMatch(r -> !from.isBefore(r.start()) && from.isBefore(r.end()));
        boolean laterRange = ranges.stream().anyMatch(r -> r.start().isAfter(from));
        return startsInside && laterRange ? Violation.CROSSES_BREAK : Violation.OUTSIDE_WORKING_HOURS;
    }

    // the grid counts from the start of each range, so 13:00 is a slot after
    // a lunch break even when the morning started at 08:45
    private static boolean onGrid(TimeRange range, LocalTime time, Duration step) {
        long minutes = Duration.between(range.start(), time).toMinutes();
        return minutes % step.toMinutes() == 0;
    }
}
