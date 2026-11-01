package io.github.viniciusclemos.booking.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that decides if a time is free for one professional: their hours,
 * the time they're away (vacation, a doctor's appointment) and what's already booked.
 * Times are in the business's own timezone.
 */
public record Calendar(WeeklySchedule schedule, List<Interval> timeOff, List<Booking> bookings, BookingPolicy policy) {

    public Calendar {
        timeOff = List.copyOf(timeOff);
        bookings = List.copyOf(bookings);
    }

    public Calendar(WeeklySchedule schedule) {
        this(schedule, List.of(), List.of(), BookingPolicy.DEFAULT);
    }

    public Calendar withBooking(Booking booking) {
        List<Booking> more = new ArrayList<>(bookings);
        more.add(booking);
        return new Calendar(schedule, timeOff, more, policy);
    }

    public Calendar withTimeOff(Interval interval) {
        List<Interval> more = new ArrayList<>(timeOff);
        more.add(interval);
        return new Calendar(schedule, more, bookings, policy);
    }

    public Calendar withPolicy(BookingPolicy newPolicy) {
        return new Calendar(schedule, timeOff, bookings, newPolicy);
    }
}
