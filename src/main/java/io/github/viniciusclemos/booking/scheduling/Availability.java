package io.github.viniciusclemos.booking.scheduling;

import io.github.viniciusclemos.booking.domain.Calendar;
import io.github.viniciusclemos.booking.domain.ServiceOffering;
import io.github.viniciusclemos.booking.domain.TimeRange;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The times a client can pick for a service. */
public final class Availability {

    private Availability() {
    }

    public static List<LocalTime> freeSlots(Calendar calendar, ServiceOffering service, LocalDate date, LocalDateTime now) {
        List<LocalTime> free = new ArrayList<>();
        long step = calendar.policy().slotStep().toMinutes();

        for (TimeRange range : calendar.schedule().rangesOn(date)) {
            LocalDateTime candidate = date.atTime(range.start());
            LocalDateTime rangeEnd = date.atTime(range.end());
            while (!candidate.plus(service.duration()).isAfter(rangeEnd)) {
                if (BookingRules.isFree(calendar, service, candidate, now)) {
                    free.add(candidate.toLocalTime());
                }
                candidate = candidate.plusMinutes(step);
            }
        }
        return free;
    }

    /** The first free time from today on, looking as far as the calendar is open. */
    public static Optional<LocalDateTime> nextFree(Calendar calendar, ServiceOffering service, LocalDateTime now) {
        LocalDate last = now.plus(calendar.policy().maxAdvance()).toLocalDate();
        for (LocalDate day = now.toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
            List<LocalTime> slots = freeSlots(calendar, service, day, now);
            if (!slots.isEmpty()) return Optional.of(day.atTime(slots.get(0)));
        }
        return Optional.empty();
    }
}
