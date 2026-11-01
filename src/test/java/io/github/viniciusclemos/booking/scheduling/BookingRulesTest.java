package io.github.viniciusclemos.booking.scheduling;

import io.github.viniciusclemos.booking.domain.Booking;
import io.github.viniciusclemos.booking.domain.BookingPolicy;
import io.github.viniciusclemos.booking.domain.Calendar;
import io.github.viniciusclemos.booking.domain.Interval;
import io.github.viniciusclemos.booking.domain.ServiceOffering;
import io.github.viniciusclemos.booking.domain.TimeRange;
import io.github.viniciusclemos.booking.domain.WeeklySchedule;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// a barbershop: weekdays 09-12 and 13-18 (lunch in between), Saturday morning, closed on Sunday
class BookingRulesTest {

    static final List<DayOfWeek> WEEKDAYS = List.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY);
    static final ServiceOffering HAIRCUT = ServiceOffering.of("Haircut", 30, 10);
    static final ServiceOffering BEARD = ServiceOffering.of("Beard", 20, 0);

    // Monday 02/11/2026, 07:00
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 7, 0);
    static final LocalDate TUESDAY_3 = LocalDate.of(2026, 11, 3);

    static Calendar barbershop() {
        WeeklySchedule hours = new WeeklySchedule()
                .on(WEEKDAYS, TimeRange.of("09:00", "12:00"), TimeRange.of("13:00", "18:00"))
                .on(DayOfWeek.SATURDAY, TimeRange.of("09:00", "13:00"));
        return new Calendar(hours);
    }

    static LocalDateTime tuesday(String time) {
        return TUESDAY_3.atTime(LocalTime.parse(time));
    }

    static List<Violation> check(Calendar calendar, ServiceOffering service, LocalDateTime start) {
        return BookingRules.check(calendar, service, start, NOW);
    }

    @Test
    void freeTimeInsideWorkingHours() {
        assertThat(check(barbershop(), HAIRCUT, tuesday("09:00"))).isEmpty();
        // ends exactly at lunch: fine
        assertThat(check(barbershop(), HAIRCUT, tuesday("11:30"))).isEmpty();
    }

    @Test
    void runningIntoLunchIsABreakProblem() {
        assertThat(check(barbershop(), HAIRCUT, tuesday("11:45"))).containsExactly(Violation.CROSSES_BREAK);
    }

    @Test
    void afterClosingOrBeforeOpening() {
        assertThat(check(barbershop(), HAIRCUT, tuesday("17:45"))).containsExactly(Violation.OUTSIDE_WORKING_HOURS);
        assertThat(check(barbershop(), HAIRCUT, tuesday("08:30"))).containsExactly(Violation.OUTSIDE_WORKING_HOURS);
        assertThat(check(barbershop(), HAIRCUT, tuesday("12:15"))).containsExactly(Violation.OUTSIDE_WORKING_HOURS);
    }

    @Test
    void sundayIsClosed() {
        LocalDateTime sunday = LocalDateTime.of(2026, 11, 8, 10, 0);
        assertThat(check(barbershop(), HAIRCUT, sunday)).containsExactly(Violation.CLOSED_THAT_DAY);
    }

    @Test
    void timesOffTheGrid() {
        assertThat(check(barbershop(), HAIRCUT, tuesday("09:07"))).containsExactly(Violation.OFF_GRID);
    }

    @Test
    void noticeAndHowFarAhead() {
        assertThat(check(barbershop(), HAIRCUT, LocalDateTime.of(2026, 11, 2, 6, 0)))
                .contains(Violation.IN_THE_PAST);
        // 2h of notice: 09:00 is too soon at 07:30, 09:30 is fine
        LocalDateTime later = NOW.plusMinutes(30);
        assertThat(BookingRules.check(barbershop(), HAIRCUT, LocalDateTime.of(2026, 11, 2, 9, 0), later))
                .containsExactly(Violation.TOO_SOON);
        assertThat(BookingRules.check(barbershop(), HAIRCUT, LocalDateTime.of(2026, 11, 2, 9, 30), later))
                .isEmpty();
        // the calendar is open 60 days ahead
        assertThat(check(barbershop(), HAIRCUT, LocalDateTime.of(2027, 1, 5, 10, 0)))
                .containsExactly(Violation.TOO_FAR_AHEAD);
    }

    @Test
    void sameTimeAsAnotherClientClashes() {
        Calendar calendar = barbershop().withBooking(new Booking(HAIRCUT, tuesday("10:00")));
        assertThat(check(calendar, BEARD, tuesday("10:00"))).containsExactly(Violation.CLASHES_WITH_BOOKING);
        assertThat(check(calendar, HAIRCUT, tuesday("09:45"))).containsExactly(Violation.CLASHES_WITH_BOOKING);
    }

    @Test
    void theBufferBlocksTheNextClient() {
        // haircut 10:00-10:30 plus 10 min of cleaning
        Calendar calendar = barbershop().withBooking(new Booking(HAIRCUT, tuesday("10:00")));
        assertThat(check(calendar, BEARD, tuesday("10:30"))).containsExactly(Violation.CLASHES_WITH_BOOKING);
        assertThat(check(calendar, BEARD, tuesday("10:45"))).isEmpty();
        // and my own buffer can't eat into the next client's time: 09:30 + 30 + 10 = 10:10
        assertThat(check(calendar, HAIRCUT, tuesday("09:30"))).containsExactly(Violation.CLASHES_WITH_BOOKING);
        assertThat(check(calendar, HAIRCUT, tuesday("09:15"))).isEmpty();
    }

    @Test
    void backToBackWithoutBufferIsFine() {
        // a 10 min grid so 10:20 is a real slot
        Calendar calendar = barbershop()
                .withPolicy(new BookingPolicy(Duration.ofHours(2), Duration.ofDays(60), Duration.ofMinutes(10)))
                .withBooking(new Booking(BEARD, tuesday("10:00")));
        assertThat(check(calendar, BEARD, tuesday("10:20"))).isEmpty();
        assertThat(check(calendar, BEARD, tuesday("09:40"))).isEmpty();
    }

    @Test
    void theBufferMayRunPastClosing() {
        assertThat(check(barbershop(), HAIRCUT, tuesday("17:30"))).isEmpty();
    }

    @Test
    void awayTime() {
        Calendar calendar = barbershop().withTimeOff(new Interval(tuesday("14:00"), tuesday("15:30")));
        assertThat(check(calendar, HAIRCUT, tuesday("13:45"))).containsExactly(Violation.TIME_OFF);
        assertThat(check(calendar, HAIRCUT, tuesday("15:30"))).isEmpty();
    }

    @Test
    void aSpecialDateReplacesItsWeekday() {
        Calendar calendar = barbershop();
        calendar.schedule().onDate(TUESDAY_3, TimeRange.of("09:00", "11:00"));
        assertThat(check(calendar, HAIRCUT, tuesday("14:00"))).containsExactly(Violation.OUTSIDE_WORKING_HOURS);

        calendar.schedule().onDate(TUESDAY_3);
        assertThat(check(calendar, HAIRCUT, tuesday("09:00"))).containsExactly(Violation.CLOSED_THAT_DAY);
    }

    @Test
    void severalProblemsAtOnce() {
        Calendar calendar = barbershop()
                .withBooking(new Booking(HAIRCUT, tuesday("11:30")))
                .withTimeOff(new Interval(tuesday("11:00"), tuesday("12:00")));
        assertThat(check(calendar, HAIRCUT, tuesday("11:45")))
                .containsExactlyInAnyOrder(Violation.CROSSES_BREAK, Violation.TIME_OFF, Violation.CLASHES_WITH_BOOKING);
    }

    @Test
    void badSetupIsRefused() {
        assertThatThrownBy(() -> TimeRange.of("12:00", "09:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeeklySchedule().on(MONDAY, TimeRange.of("09:00", "12:00"), TimeRange.of("11:00", "14:00")))
                .hasMessageContaining("overlap");
        assertThatThrownBy(() -> ServiceOffering.of("Nothing", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingPolicy(Duration.ofDays(2), Duration.ofDays(1), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
