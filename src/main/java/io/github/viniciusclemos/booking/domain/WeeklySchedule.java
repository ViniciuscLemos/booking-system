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
    // one person's own week, when they don't work every hour the place is open; null means they do
    private Map<DayOfWeek, List<TimeRange>> own;

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

    /**
     * Narrows the hours to one person's week. Their hours never go past the place's:
     * someone down for 08:00-14:00 at a place that opens at 09:00 starts at 09:00,
     * and a holiday closes the place for everyone.
     */
    public WeeklySchedule onlyWhen(Map<DayOfWeek, List<TimeRange>> theirs) {
        own = new EnumMap<>(DayOfWeek.class);
        theirs.forEach((day, ranges) -> own.put(day, checked(ranges)));
        return this;
    }

    public List<TimeRange> rangesOn(LocalDate date) {
        List<TimeRange> special = overrides.get(date);
        List<TimeRange> place = special != null ? special : week.getOrDefault(date.getDayOfWeek(), List.of());
        if (own == null) return place;
        return intersect(place, own.getOrDefault(date.getDayOfWeek(), List.of()));
    }

    /** The stretches that are in both lists, e.g. 09-12 + 13-18 with 11-15 gives 11-12 and 13-15. */
    static List<TimeRange> intersect(List<TimeRange> a, List<TimeRange> b) {
        List<TimeRange> both = new ArrayList<>();
        for (TimeRange x : a) {
            for (TimeRange y : b) {
                if (!x.overlaps(y)) continue;
                var start = x.start().isAfter(y.start()) ? x.start() : y.start();
                var end = x.end().isBefore(y.end()) ? x.end() : y.end();
                both.add(new TimeRange(start, end));
            }
        }
        both.sort(Comparator.comparing(TimeRange::start));
        return List.copyOf(both);
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
