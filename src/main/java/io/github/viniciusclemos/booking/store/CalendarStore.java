package io.github.viniciusclemos.booking.store;

import io.github.viniciusclemos.booking.domain.BookingPolicy;
import io.github.viniciusclemos.booking.domain.Calendar;
import io.github.viniciusclemos.booking.domain.Interval;
import io.github.viniciusclemos.booking.domain.TimeRange;
import io.github.viniciusclemos.booking.domain.WeeklySchedule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Repository
public class CalendarStore {

    private final JdbcClient db;

    public CalendarStore(JdbcClient db) {
        this.db = db;
    }

    /**
     * One professional's calendar between two dates (both included), in a handful of
     * queries, so looking at two weeks of availability doesn't mean one query per day.
     * The hours are the place's; the time off is theirs plus the place's own (holidays).
     */
    public Calendar load(LocalDate from, LocalDate to, BookingPolicy policy, long professionalId) {
        WeeklySchedule schedule = new WeeklySchedule();
        weeklyHours().forEach((day, ranges) -> schedule.on(day, ranges.toArray(TimeRange[]::new)));
        overrides(from, to).forEach((date, ranges) -> schedule.onDate(date, ranges.toArray(TimeRange[]::new)));
        ownHours(professionalId).ifPresent(schedule::onlyWhen);

        // one extra day at the end, for a buffer that runs past midnight
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(2).atStartOfDay();
        List<Interval> timeOff = db.sql("""
                        SELECT starts, ends FROM time_off
                        WHERE starts < ? AND ends > ? AND (professional_id IS NULL OR professional_id = ?)""")
                .params(end, start, professionalId)
                .query((rs, n) -> new Interval(rs.getObject(1, LocalDateTime.class), rs.getObject(2, LocalDateTime.class)))
                .list();
        var bookings = db.sql(BOOKING_SELECT
                        + " WHERE b.status = 'CONFIRMED' AND b.professional_id = ? AND b.starts < ? AND b.blocked_until > ?"
                        + " ORDER BY b.starts")
                .params(professionalId, end, start).query(BookingRow.class).list()
                .stream().map(BookingRow::toBooking).toList();
        return new Calendar(schedule, timeOff, bookings, policy);
    }

    private Optional<Map<DayOfWeek, List<TimeRange>>> ownHours(long professionalId) {
        Map<DayOfWeek, List<TimeRange>> week = new TreeMap<>();
        db.sql("SELECT day_of_week, starts, ends FROM professional_hours WHERE professional_id = ?")
                .param(professionalId)
                .query(rs -> {
                    week.computeIfAbsent(DayOfWeek.of(rs.getInt(1)), d -> new ArrayList<>())
                            .add(new TimeRange(rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class)));
                });
        return week.isEmpty() ? Optional.empty() : Optional.of(week);
    }

    /** Only the place's hours for a range of dates, with nobody's bookings in it. */
    public WeeklySchedule schedule(LocalDate from, LocalDate to) {
        WeeklySchedule schedule = new WeeklySchedule();
        weeklyHours().forEach((day, ranges) -> schedule.on(day, ranges.toArray(TimeRange[]::new)));
        overrides(from, to).forEach((date, ranges) -> schedule.onDate(date, ranges.toArray(TimeRange[]::new)));
        return schedule;
    }

    public Map<DayOfWeek, List<TimeRange>> weeklyHours() {
        Map<DayOfWeek, List<TimeRange>> week = new TreeMap<>();
        db.sql("SELECT day_of_week, starts, ends FROM working_hours ORDER BY day_of_week, starts")
                .query(rs -> {
                    week.computeIfAbsent(DayOfWeek.of(rs.getInt(1)), d -> new ArrayList<>())
                            .add(new TimeRange(rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class)));
                });
        return week;
    }

    public Map<LocalDate, List<TimeRange>> overrides(LocalDate from, LocalDate to) {
        Map<LocalDate, List<TimeRange>> byDate = new LinkedHashMap<>();
        db.sql("SELECT day, starts, ends FROM date_override WHERE day BETWEEN ? AND ? ORDER BY day, starts")
                .params(from, to)
                .query(rs -> {
                    List<TimeRange> ranges = byDate.computeIfAbsent(rs.getObject(1, LocalDate.class), d -> new ArrayList<>());
                    LocalTime starts = rs.getObject(2, LocalTime.class);
                    // a row with no times only marks the date as closed
                    if (starts != null) ranges.add(new TimeRange(starts, rs.getObject(3, LocalTime.class)));
                });
        return byDate;
    }

    public List<BookingRow> confirmedBetween(LocalDateTime from, LocalDateTime to) {
        return db.sql(BOOKING_SELECT
                        + " WHERE b.status = 'CONFIRMED' AND b.starts < ? AND b.blocked_until > ? ORDER BY b.starts")
                .params(to, from).query(BookingRow.class).list();
    }

    public Optional<BookingRow> findBooking(long id) {
        return db.sql(BOOKING_SELECT + " WHERE b.id = ?").param(id).query(BookingRow.class).optional();
    }

    public long insertBooking(long serviceId, long professionalId, String clientName, String clientPhone,
                              LocalDateTime starts, LocalDateTime ends, LocalDateTime blockedUntil) {
        return insertBooking(serviceId, professionalId, clientName, clientPhone, starts, ends, blockedUntil, null);
    }

    public long insertBooking(long serviceId, long professionalId, String clientName, String clientPhone,
                              LocalDateTime starts, LocalDateTime ends, LocalDateTime blockedUntil,
                              String cancelTokenHash) {
        return db.sql("""
                        INSERT INTO booking (service_id, professional_id, client_name, client_phone, starts, ends,
                                             blocked_until, cancel_token_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(serviceId, professionalId, clientName, clientPhone, starts, ends, blockedUntil, cancelTokenHash)
                .query(Long.class).single();
    }

    /** How many confirmed bookings each person has on a day, to spread "anyone" bookings out. */
    public Map<Long, Integer> loadOn(LocalDate day) {
        Map<Long, Integer> load = new LinkedHashMap<>();
        db.sql("""
                        SELECT professional_id, count(*) FROM booking
                        WHERE status = 'CONFIRMED' AND starts >= ? AND starts < ? GROUP BY professional_id""")
                .params(day.atStartOfDay(), day.plusDays(1).atStartOfDay())
                .query(rs -> {
                    load.put(rs.getLong(1), rs.getInt(2));
                });
        return load;
    }

    public Optional<BookingRow> findByCancelToken(String tokenHash) {
        return db.sql(BOOKING_SELECT + " WHERE b.cancel_token_hash = ?").param(tokenHash)
                .query(BookingRow.class).optional();
    }

    /** Every booking that starts on the day, cancelled ones too, for the staff's agenda. */
    public List<BookingRow> bookingsOn(LocalDate day) {
        return db.sql(BOOKING_SELECT + " WHERE b.starts >= ? AND b.starts < ? ORDER BY b.starts, b.id")
                .params(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).query(BookingRow.class).list();
    }

    /** Only touches a confirmed booking, so two cancels at once can't both "win". */
    public boolean cancel(long id, String by, LocalDateTime at) {
        return db.sql("""
                        UPDATE booking SET status = 'CANCELLED', cancelled_at = ?, cancelled_by = ?
                        WHERE id = ? AND status = 'CONFIRMED'""")
                .params(at, by, id).update() == 1;
    }

    @Transactional
    public void replaceWeeklyHours(Map<DayOfWeek, List<TimeRange>> week) {
        db.sql("DELETE FROM working_hours").update();
        week.forEach((day, ranges) -> ranges.forEach(r ->
                db.sql("INSERT INTO working_hours (day_of_week, starts, ends) VALUES (?, ?, ?)")
                        .params(day.getValue(), r.start(), r.end()).update()));
    }

    @Transactional
    public void replaceDate(LocalDate date, List<TimeRange> ranges) {
        db.sql("DELETE FROM date_override WHERE day = ?").param(date).update();
        if (ranges.isEmpty()) {
            db.sql("INSERT INTO date_override (day) VALUES (?)").param(date).update();
        }
        ranges.forEach(r -> db.sql("INSERT INTO date_override (day, starts, ends) VALUES (?, ?, ?)")
                .params(date, r.start(), r.end()).update());
    }

    public boolean removeDate(LocalDate date) {
        return db.sql("DELETE FROM date_override WHERE day = ?").param(date).update() > 0;
    }

    /** professionalId and professional are null when it's the whole place that's closed. */
    public record TimeOff(long id, LocalDateTime starts, LocalDateTime ends, String reason, Long professionalId,
                          String professional) {
    }

    /** Time off that hasn't ended yet, soonest first. */
    public List<TimeOff> timeOffFrom(LocalDateTime from) {
        return db.sql("""
                        SELECT t.id, t.starts, t.ends, t.reason, t.professional_id, p.name AS professional
                        FROM time_off t LEFT JOIN professional p ON p.id = t.professional_id
                        WHERE t.ends > ? ORDER BY t.starts""")
                .param(from).query(TimeOff.class).list();
    }

    public boolean removeTimeOff(long id) {
        return db.sql("DELETE FROM time_off WHERE id = ?").param(id).update() == 1;
    }

    public long addTimeOff(LocalDateTime starts, LocalDateTime ends, String reason, Long professionalId) {
        return db.sql("INSERT INTO time_off (starts, ends, reason, professional_id) VALUES (?, ?, ?, ?) RETURNING id")
                .params(starts, ends, reason, professionalId).query(Long.class).single();
    }

    private static final String BOOKING_SELECT = """
            SELECT b.id, b.service_id, s.name AS service_name, b.professional_id, p.name AS professional_name,
                   b.client_name, b.client_phone, b.starts, b.ends, b.blocked_until, b.status, b.cancelled_at,
                   b.cancelled_by
            FROM booking b JOIN service s ON s.id = b.service_id JOIN professional p ON p.id = b.professional_id""";
}
