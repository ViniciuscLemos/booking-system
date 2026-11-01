package io.github.viniciusclemos.booking.api;

import io.github.viniciusclemos.booking.config.BookingSettings;
import io.github.viniciusclemos.booking.domain.TimeRange;
import io.github.viniciusclemos.booking.scheduling.SchedulingService;
import io.github.viniciusclemos.booking.scheduling.SchedulingService.DaySummary;
import io.github.viniciusclemos.booking.store.BookingRow;
import io.github.viniciusclemos.booking.store.CalendarStore;
import io.github.viniciusclemos.booking.store.ServiceRow;
import io.github.viniciusclemos.booking.store.ServiceStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** What the client's booking page uses. */
@RestController
@RequestMapping("/api")
public class PublicController {

    private final SchedulingService scheduling;
    private final ServiceStore services;
    private final CalendarStore calendars;
    private final BookingSettings settings;

    public PublicController(SchedulingService scheduling, ServiceStore services, CalendarStore calendars,
                            BookingSettings settings) {
        this.scheduling = scheduling;
        this.services = services;
        this.calendars = calendars;
        this.settings = settings;
    }

    public record BusinessView(String name, String zone, LocalDateTime now, Map<DayOfWeek, List<TimeRange>> hours) {
    }

    @GetMapping("/business")
    BusinessView business() {
        return new BusinessView(settings.businessName(), settings.zone().getId(), scheduling.now(),
                calendars.weeklyHours());
    }

    @GetMapping("/services")
    List<ServiceRow> services() {
        return services.findActive();
    }

    public record SlotsView(long serviceId, LocalDate date, List<LocalTime> slots) {
    }

    @GetMapping("/availability")
    SlotsView availability(@RequestParam long serviceId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new SlotsView(serviceId, date, scheduling.slots(serviceId, date));
    }

    @GetMapping("/availability/days")
    List<DaySummary> days(@RequestParam long serviceId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(defaultValue = "14") int count) {
        if (count < 1 || count > 62) throw new IllegalArgumentException("count must be between 1 and 62");
        return scheduling.days(serviceId, from != null ? from : scheduling.now().toLocalDate(), count);
    }

    public record NewBooking(
            @NotNull Long serviceId,
            @NotNull LocalDateTime start,
            @NotBlank @Size(max = 80) String clientName,
            // digits, spaces and the usual phone symbols; at least 8 digits is checked below
            @NotBlank @Pattern(regexp = "[0-9 ()+\\-]{8,20}", message = "must be a phone number") String clientPhone) {
    }

    public record BookingView(long id, long serviceId, String service, String clientName, LocalDateTime starts,
                              LocalDateTime ends, String status) {

        static BookingView of(BookingRow row) {
            return new BookingView(row.id(), row.serviceId(), row.serviceName(), row.clientName(), row.starts(),
                    row.ends(), row.status());
        }
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView book(@Valid @RequestBody NewBooking body) {
        String phone = body.clientPhone().trim();
        if (phone.chars().filter(Character::isDigit).count() < 8) {
            throw new IllegalArgumentException("The phone needs at least 8 digits");
        }
        return BookingView.of(scheduling.book(body.serviceId(), body.start().withSecond(0).withNano(0),
                body.clientName().trim(), phone));
    }

    // the phone stays out of the public view; the business sees it in the admin list
    @GetMapping("/bookings/{id}")
    BookingView booking(@PathVariable long id) {
        return BookingView.of(scheduling.booking(id));
    }
}
