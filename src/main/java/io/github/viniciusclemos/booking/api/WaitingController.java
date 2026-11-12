package io.github.viniciusclemos.booking.api;

import io.github.viniciusclemos.booking.scheduling.NotFound;
import io.github.viniciusclemos.booking.scheduling.SchedulingService;
import io.github.viniciusclemos.booking.store.ProfessionalStore;
import io.github.viniciusclemos.booking.store.ServiceStore;
import io.github.viniciusclemos.booking.store.WaitingStore;
import io.github.viniciusclemos.booking.store.WaitingStore.Waiting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The waiting list for a full day. The client leaves a name and a phone; the staff sees them
 * on that day's agenda and calls the first one when a time opens up.
 */
@RestController
public class WaitingController {

    // same as how far ahead the calendar opens
    private static final int DAYS_AHEAD = 60;

    private final WaitingStore waiting;
    private final ServiceStore services;
    private final ProfessionalStore professionals;
    private final SchedulingService scheduling;

    public WaitingController(WaitingStore waiting, ServiceStore services, ProfessionalStore professionals,
                             SchedulingService scheduling) {
        this.waiting = waiting;
        this.services = services;
        this.professionals = professionals;
        this.scheduling = scheduling;
    }

    public record Join(
            @NotNull Long serviceId,
            @NotNull LocalDate date,
            @NotBlank @Size(max = 80) String clientName,
            @NotBlank @Pattern(regexp = "[0-9 ()+\\-]{8,20}", message = "must be a phone number") String clientPhone,
            Long professionalId) {
    }

    @PostMapping("/api/waiting")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Integer> join(@Valid @RequestBody Join body) {
        String phone = body.clientPhone().trim();
        if (phone.chars().filter(Character::isDigit).count() < 8) {
            throw new IllegalArgumentException("The phone needs at least 8 digits");
        }
        LocalDate today = scheduling.now().toLocalDate();
        if (body.date().isBefore(today) || body.date().isAfter(today.plusDays(DAYS_AHEAD))) {
            throw new IllegalArgumentException("That day can't be booked");
        }
        var service = services.findById(body.serviceId()).filter(s -> s.active())
                .orElseThrow(() -> new NotFound("Service not found"));
        if (body.professionalId() != null && professionals.doing(service.id()).stream()
                .noneMatch(p -> p.id() == body.professionalId())) {
            throw new NotFound("Nobody by that id does this service");
        }
        int place = waiting.add(service.id(), body.professionalId(), body.date(), body.clientName().trim(), phone);
        return Map.of("place", place);
    }

    @GetMapping("/api/admin/waiting")
    List<Waiting> onDay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return waiting.on(date);
    }

    @DeleteMapping("/api/admin/waiting/{id}")
    ResponseEntity<Void> remove(@PathVariable long id) {
        if (!waiting.remove(id)) throw new NotFound("Not on the waiting list");
        return ResponseEntity.noContent().build();
    }
}
