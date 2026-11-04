package io.github.viniciusclemos.booking.scheduling;

import io.github.viniciusclemos.booking.config.BookingSettings;
import io.github.viniciusclemos.booking.domain.Booking;
import io.github.viniciusclemos.booking.domain.BookingPolicy;
import io.github.viniciusclemos.booking.domain.Calendar;
import io.github.viniciusclemos.booking.store.BookingRow;
import io.github.viniciusclemos.booking.store.CalendarStore;
import io.github.viniciusclemos.booking.store.ServiceRow;
import io.github.viniciusclemos.booking.store.ServiceStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class SchedulingService {

    private final ServiceStore services;
    private final CalendarStore calendars;
    private final BookingSettings settings;
    private final Clock clock;

    public SchedulingService(ServiceStore services, CalendarStore calendars, BookingSettings settings, Clock clock) {
        this.services = services;
        this.calendars = calendars;
        this.settings = settings;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).withSecond(0).withNano(0);
    }

    public List<LocalTime> slots(long serviceId, LocalDate date) {
        ServiceRow service = activeService(serviceId);
        Calendar calendar = calendars.load(date, date, settings.policy());
        return Availability.freeSlots(calendar, service.toOffering(), date, now());
    }

    public record DaySummary(LocalDate date, boolean open, int free, LocalTime first) {
    }

    /** How each day looks for this service, loading the whole range at once. */
    public List<DaySummary> days(long serviceId, LocalDate from, int count) {
        ServiceRow service = activeService(serviceId);
        LocalDate to = from.plusDays(count - 1);
        Calendar calendar = calendars.load(from, to, settings.policy());
        LocalDateTime now = now();

        List<DaySummary> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            boolean open = !calendar.schedule().rangesOn(day).isEmpty();
            List<LocalTime> free = Availability.freeSlots(calendar, service.toOffering(), day, now);
            days.add(new DaySummary(day, open, free.size(), free.isEmpty() ? null : free.get(0)));
        }
        return days;
    }

    /** A new booking and the token of its cancel link, which exists only in this answer. */
    public record Booked(BookingRow booking, String cancelToken) {
    }

    public Booked book(long serviceId, LocalDateTime start, String clientName, String clientPhone) {
        ServiceRow service = activeService(serviceId);
        Booking wanted = new Booking(service.toOffering(), start);

        Calendar calendar = calendars.load(start.toLocalDate(), start.toLocalDate(), settings.policy());
        List<Violation> problems = BookingRules.check(calendar, wanted.service(), start, now());
        if (!problems.isEmpty()) throw new BookingRefused(problems);

        String token = CancelTokens.newToken();
        try {
            long id = calendars.insertBooking(serviceId, clientName, clientPhone, start,
                    wanted.appointment().end(), wanted.blocked().end(), CancelTokens.hash(token));
            return new Booked(booking(id), token);
        } catch (DataIntegrityViolationException e) {
            // someone took the same time between the check above and the insert
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("booking_no_overlap")) {
                throw new BookingRefused(List.of(Violation.CLASHES_WITH_BOOKING));
            }
            throw e;
        }
    }

    public BookingRow booking(long id) {
        return calendars.findBooking(id).orElseThrow(() -> new NotFound("Booking not found"));
    }

    public BookingPolicy policy() {
        return settings.policy();
    }

    /** Until when the client can cancel this booking alone. */
    public LocalDateTime cancelDeadline(BookingRow booking) {
        return booking.starts().minus(settings.cancelUntil());
    }

    public boolean clientCanCancel(BookingRow booking) {
        return booking.confirmed() && now().isBefore(cancelDeadline(booking));
    }

    public BookingRow byCancelToken(String token) {
        return calendars.findByCancelToken(CancelTokens.hash(token))
                .orElseThrow(() -> new NotFound("This cancel link isn't valid."));
    }

    public BookingRow cancelByClient(String token) {
        BookingRow booking = byCancelToken(token);
        if (!booking.confirmed()) throw new CancelRefused("This booking was already cancelled.");
        if (!clientCanCancel(booking)) {
            throw new CancelRefused("It's too close to the time to cancel online. Please give us a call.");
        }
        return cancel(booking, "CLIENT");
    }

    /** The staff can cancel at any time, a client may call at the last minute. */
    public BookingRow cancelByStaff(long id) {
        BookingRow booking = booking(id);
        if (!booking.confirmed()) throw new CancelRefused("This booking was already cancelled.");
        return cancel(booking, "STAFF");
    }

    private BookingRow cancel(BookingRow booking, String by) {
        if (!calendars.cancel(booking.id(), by, now())) {
            // someone else cancelled it a moment ago
            throw new CancelRefused("This booking was already cancelled.");
        }
        return booking(booking.id());
    }

    private ServiceRow activeService(long id) {
        return services.findById(id).filter(ServiceRow::active)
                .orElseThrow(() -> new NotFound("Service not found"));
    }
}
