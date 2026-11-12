package io.github.viniciusclemos.booking.api;

import io.github.viniciusclemos.booking.scheduling.NotFound;
import io.github.viniciusclemos.booking.scheduling.SchedulingService;
import io.github.viniciusclemos.booking.store.CalendarStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The month in numbers for the owner: how many came, how much it made, who didn't show up,
 * who cancelled, and how it splits by person, service, weekday and hour. Money counts only
 * the bookings that happened (confirmed, time passed, not a no-show), at the price they
 * were booked for.
 */
@RestController
@RequestMapping("/api/admin")
public class ReportController {

    private final JdbcClient db;
    private final CalendarStore calendars;
    private final SchedulingService scheduling;

    public ReportController(JdbcClient db, CalendarStore calendars, SchedulingService scheduling) {
        this.db = db;
        this.calendars = calendars;
        this.scheduling = scheduling;
    }

    public record NoShow(boolean noShow) {
    }

    @PostMapping("/bookings/{id}/no-show")
    ResponseEntity<Void> noShow(@PathVariable long id, @RequestBody NoShow body) {
        if (!calendars.markNoShow(id, body.noShow(), scheduling.now())) {
            throw new NotFound("No confirmed booking with that id whose time has passed");
        }
        return ResponseEntity.noContent().build();
    }

    public record Totals(int done, int coming, int noShows, int cancelledByClient, int cancelledByStaff, long earnedCents) {
    }

    public record Line(String name, int done, long earnedCents) {
    }

    public record Slot(int value, int done) {
    }

    public record Report(String month, Totals totals, Totals previous, List<Line> people, List<Line> services,
                         List<Slot> weekdays, List<Slot> hours) {
    }

    @GetMapping("/report")
    Report report(@RequestParam String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Months look like 2026-11");
        }
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.plusMonths(1).atDay(1);
        var now = scheduling.now();
        // "happened": confirmed, already started, and the client came
        String happened = "b.status = 'CONFIRMED' AND NOT b.no_show AND b.starts < :now";
        String in = "b.starts >= :from AND b.starts < :to";

        List<Line> people = db.sql("""
                        SELECT p.name, count(*) AS done, coalesce(sum(b.price_cents), 0) AS earned_cents
                        FROM booking b JOIN professional p ON p.id = b.professional_id
                        WHERE %s AND %s GROUP BY p.name ORDER BY earned_cents DESC, p.name""".formatted(in, happened))
                .param("from", from.atStartOfDay()).param("to", to.atStartOfDay()).param("now", now)
                .query(Line.class).list();
        List<Line> services = db.sql("""
                        SELECT s.name, count(*) AS done, coalesce(sum(b.price_cents), 0) AS earned_cents
                        FROM booking b JOIN service s ON s.id = b.service_id
                        WHERE %s AND %s GROUP BY s.name ORDER BY done DESC, s.name""".formatted(in, happened))
                .param("from", from.atStartOfDay()).param("to", to.atStartOfDay()).param("now", now)
                .query(Line.class).list();
        // every weekday and every hour, the empty ones as zero, so the bars line up
        List<Slot> weekdays = db.sql("""
                        SELECT d AS value, count(b.id) AS done FROM generate_series(1, 7) AS d
                        LEFT JOIN booking b ON extract(isodow FROM b.starts) = d AND %s AND %s
                        GROUP BY d ORDER BY d""".formatted(in, happened))
                .param("from", from.atStartOfDay()).param("to", to.atStartOfDay()).param("now", now)
                .query(Slot.class).list();
        List<Slot> hours = db.sql("""
                        SELECT h AS value, count(b.id) AS done FROM generate_series(7, 21) AS h
                        LEFT JOIN booking b ON extract(hour FROM b.starts) = h AND %s AND %s
                        GROUP BY h ORDER BY h""".formatted(in, happened))
                .param("from", from.atStartOfDay()).param("to", to.atStartOfDay()).param("now", now)
                .query(Slot.class).list();

        return new Report(ym.toString(), totals(from, to), totals(from.minusMonths(1), from), people, services,
                weekdays, hours);
    }

    private Totals totals(LocalDate from, LocalDate to) {
        return db.sql("""
                        SELECT count(*) FILTER (WHERE status = 'CONFIRMED' AND NOT no_show AND starts < :now) AS done,
                               count(*) FILTER (WHERE status = 'CONFIRMED' AND starts >= :now) AS coming,
                               count(*) FILTER (WHERE status = 'CONFIRMED' AND no_show) AS no_shows,
                               count(*) FILTER (WHERE status = 'CANCELLED' AND cancelled_by = 'CLIENT') AS cancelled_by_client,
                               count(*) FILTER (WHERE status = 'CANCELLED' AND cancelled_by <> 'CLIENT') AS cancelled_by_staff,
                               coalesce(sum(price_cents) FILTER (WHERE status = 'CONFIRMED' AND NOT no_show AND starts < :now), 0)
                                   AS earned_cents
                        FROM booking WHERE starts >= :from AND starts < :to""")
                .param("from", from.atStartOfDay()).param("to", to.atStartOfDay()).param("now", scheduling.now())
                .query(Totals.class).single();
    }
}
