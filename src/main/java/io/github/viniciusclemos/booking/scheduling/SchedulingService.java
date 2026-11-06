package io.github.viniciusclemos.booking.scheduling;

import io.github.viniciusclemos.booking.config.BookingSettings;
import io.github.viniciusclemos.booking.domain.Booking;
import io.github.viniciusclemos.booking.domain.BookingPolicy;
import io.github.viniciusclemos.booking.domain.Calendar;
import io.github.viniciusclemos.booking.store.BookingRow;
import io.github.viniciusclemos.booking.store.CalendarStore;
import io.github.viniciusclemos.booking.store.ProfessionalStore;
import io.github.viniciusclemos.booking.store.ProfessionalStore.Professional;
import io.github.viniciusclemos.booking.store.ServiceRow;
import io.github.viniciusclemos.booking.store.ServiceStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
public class SchedulingService {

    private final ServiceStore services;
    private final CalendarStore calendars;
    private final ProfessionalStore professionals;
    private final BookingSettings settings;
    private final Clock clock;

    public SchedulingService(ServiceStore services, CalendarStore calendars, ProfessionalStore professionals,
                             BookingSettings settings, Clock clock) {
        this.services = services;
        this.calendars = calendars;
        this.professionals = professionals;
        this.settings = settings;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).withSecond(0).withNano(0);
    }

    /**
     * The free times with one person, or with anyone (professionalId null): then a time is
     * free if at least one of them is free at it.
     */
    public List<LocalTime> slots(long serviceId, LocalDate date, Long professionalId) {
        ServiceRow service = activeService(serviceId);
        LocalDateTime now = now();
        TreeSet<LocalTime> free = new TreeSet<>();
        for (Professional who : who(professionalId)) {
            Calendar calendar = calendars.load(date, date, settings.policy(), who.id());
            free.addAll(Availability.freeSlots(calendar, service.toOffering(), date, now));
        }
        return List.copyOf(free);
    }

    public List<LocalTime> slots(long serviceId, LocalDate date) {
        return slots(serviceId, date, null);
    }

    public record DaySummary(LocalDate date, boolean open, int free, LocalTime first) {
    }

    /** How each day looks for this service, loading the whole range at once per person. */
    public List<DaySummary> days(long serviceId, LocalDate from, int count, Long professionalId) {
        ServiceRow service = activeService(serviceId);
        LocalDate to = from.plusDays(count - 1);
        List<Calendar> each = who(professionalId).stream()
                .map(p -> calendars.load(from, to, settings.policy(), p.id())).toList();
        var schedule = calendars.schedule(from, to);
        LocalDateTime now = now();

        List<DaySummary> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            boolean open = !schedule.rangesOn(day).isEmpty();
            TreeSet<LocalTime> free = new TreeSet<>();
            for (Calendar calendar : each) free.addAll(Availability.freeSlots(calendar, service.toOffering(), day, now));
            days.add(new DaySummary(day, open, free.size(), free.isEmpty() ? null : free.first()));
        }
        return days;
    }

    public List<DaySummary> days(long serviceId, LocalDate from, int count) {
        return days(serviceId, from, count, null);
    }

    /** A new booking and the token of its cancel link, which exists only in this answer. */
    public record Booked(BookingRow booking, String cancelToken) {
    }

    /**
     * Books with that person, or with anyone free at that time: the one with the fewest
     * bookings that day first, so the work is spread out instead of always falling on the first.
     */
    public Booked book(long serviceId, LocalDateTime start, String clientName, String clientPhone, Long professionalId) {
        ServiceRow service = activeService(serviceId);
        Booking wanted = new Booking(service.toOffering(), start);
        List<Professional> candidates = new ArrayList<>(who(professionalId));
        Map<Long, Integer> load = calendars.loadOn(start.toLocalDate());
        candidates.sort(Comparator.comparingInt((Professional p) -> load.getOrDefault(p.id(), 0)).thenComparingLong(Professional::id));

        List<Violation> firstProblems = null;
        for (Professional who : candidates) {
            Calendar calendar = calendars.load(start.toLocalDate(), start.toLocalDate(), settings.policy(), who.id());
            List<Violation> problems = BookingRules.check(calendar, wanted.service(), start, now());
            if (!problems.isEmpty()) {
                if (firstProblems == null) firstProblems = problems;
                continue;
            }
            String token = CancelTokens.newToken();
            try {
                long id = calendars.insertBooking(serviceId, who.id(), clientName, clientPhone, start,
                        wanted.appointment().end(), wanted.blocked().end(), CancelTokens.hash(token));
                return new Booked(booking(id), token);
            } catch (DataIntegrityViolationException e) {
                // someone took the same time with this person between the check and the insert
                if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("booking_no_overlap")) throw e;
                if (firstProblems == null) firstProblems = List.of(Violation.CLASHES_WITH_BOOKING);
            }
        }
        throw new BookingRefused(firstProblems != null ? firstProblems : List.of(Violation.CLASHES_WITH_BOOKING));
    }

    public Booked book(long serviceId, LocalDateTime start, String clientName, String clientPhone) {
        return book(serviceId, start, clientName, clientPhone, null);
    }

    /** One person (who has to be taking bookings), or everyone who is. */
    private List<Professional> who(Long professionalId) {
        if (professionalId == null) return professionals.active();
        return List.of(professionals.find(professionalId).filter(Professional::active)
                .orElseThrow(() -> new NotFound("Professional not found")));
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
