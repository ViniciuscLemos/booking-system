package io.github.viniciusclemos.booking.api;

import io.github.viniciusclemos.booking.domain.TimeRange;
import io.github.viniciusclemos.booking.domain.WeeklySchedule;
import io.github.viniciusclemos.booking.scheduling.NotFound;
import io.github.viniciusclemos.booking.store.BookingRow;
import io.github.viniciusclemos.booking.store.CalendarStore;
import io.github.viniciusclemos.booking.store.ServiceRow;
import io.github.viniciusclemos.booking.store.ServiceStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The business's side: services, opening hours and the day's bookings.
 * No login yet, that's the next step, so this isn't meant to be exposed as is.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ServiceStore services;
    private final CalendarStore calendars;

    public AdminController(ServiceStore services, CalendarStore calendars) {
        this.services = services;
        this.calendars = calendars;
    }

    public record ServiceForm(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 300) String description,
            @NotNull @Min(5) @Max(480) Integer durationMin,
            @PositiveOrZero @Max(120) Integer bufferMin,
            @NotNull @PositiveOrZero Long priceCents,
            Boolean active) {

        int buffer() {
            return bufferMin == null ? 0 : bufferMin;
        }
    }

    @GetMapping("/services")
    List<ServiceRow> services() {
        return services.findAll();
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    ServiceRow create(@Valid @RequestBody ServiceForm form) {
        long id = services.create(form.name().trim(), form.description(), form.durationMin(), form.buffer(),
                form.priceCents());
        return services.findById(id).orElseThrow();
    }

    // changing a service doesn't touch bookings that already exist: they keep their own times
    @PutMapping("/services/{id}")
    ServiceRow update(@PathVariable long id, @Valid @RequestBody ServiceForm form) {
        boolean found = services.update(id, form.name().trim(), form.description(), form.durationMin(), form.buffer(),
                form.priceCents(), form.active() == null || form.active());
        if (!found) throw new NotFound("Service not found");
        return services.findById(id).orElseThrow();
    }

    /** The whole week at once, e.g. {"MONDAY": [{"start":"09:00","end":"12:00"}, ...]}. */
    @PutMapping("/hours")
    Map<DayOfWeek, List<TimeRange>> hours(@RequestBody Map<DayOfWeek, List<TimeRange>> week) {
        // goes through WeeklySchedule just to refuse overlapping ranges before saving
        WeeklySchedule check = new WeeklySchedule();
        week.forEach((day, ranges) -> check.on(day, ranges.toArray(TimeRange[]::new)));
        calendars.replaceWeeklyHours(new EnumMap<>(week));
        return calendars.weeklyHours();
    }

    /** Special hours for one date; an empty list closes it. */
    @PutMapping("/dates/{date}")
    List<TimeRange> date(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @RequestBody List<TimeRange> ranges) {
        new WeeklySchedule().onDate(date, ranges.toArray(TimeRange[]::new));
        calendars.replaceDate(date, ranges);
        return ranges;
    }

    public record TimeOffForm(@NotNull LocalDateTime starts, @NotNull LocalDateTime ends, @Size(max = 200) String reason) {
    }

    @PostMapping("/time-off")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> timeOff(@Valid @RequestBody TimeOffForm form) {
        if (!form.starts().isBefore(form.ends())) throw new IllegalArgumentException("starts must be before ends");
        return Map.of("id", calendars.addTimeOff(form.starts(), form.ends(), form.reason()));
    }

    @GetMapping("/bookings")
    List<BookingRow> bookings(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return calendars.confirmedBetween(date.atStartOfDay(), date.plusDays(1).atStartOfDay()).stream()
                .filter(b -> b.starts().toLocalDate().equals(date)).toList();
    }
}
