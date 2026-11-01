package io.github.viniciusclemos.booking.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Opening hours. Each weekday has its own ranges (a lunch break is just the gap
 * between two of them), and a specific date can replace its weekday, like a
 * Saturday before a holiday that only opens in the morning.
 */
public class WeeklySchedule {

    private final Map<DayOfWeek, List<TimeRange>> week = new EnumMap<>(DayOfWeek.class);
    private final Map<LocalDate, List<TimeRange>> overrides = new HashMap<>();

    public WeeklySchedule on(DayOfWeek day, TimeRange... ranges) {
        week.put(day, checked(List.of(ranges)));
        return this;
    }

    /** Same hours on several days, e.g. Monday to Friday. */
    public WeeklySchedule on(List<DayOfWeek> days, TimeRange... ranges) {
        days.forEach(day -> on(day, ranges));
        return this;
    }

    /** Hours just for this date. No ranges means closed that day. */
    public WeeklySchedule onDate(LocalDate date, TimeRange... ranges) {
        overrides.put(date, checked(List.of(ranges)));
        return this;
    }

    public List<TimeRange> rangesOn(LocalDate date) {
        List<TimeRange> special = overrides.get(date);
        if (special != null) return special;
        return week.getOrDefault(date.getDayOfWeek(), List.of());
    }

    private static List<TimeRange> checked(List<TimeRange> ranges) {
        List<TimeRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(TimeRange::start));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).overlaps(sorted.get(i))) {
                throw new IllegalArgumentException("ranges overlap: " + sorted.get(i - 1) + " and " + sorted.get(i));
            }
        }
        return List.copyOf(sorted);
    }
}
