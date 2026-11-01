package io.github.viniciusclemos.booking.scheduling;

import io.github.viniciusclemos.booking.domain.Booking;
import io.github.viniciusclemos.booking.domain.BookingPolicy;
import io.github.viniciusclemos.booking.domain.Calendar;
import io.github.viniciusclemos.booking.domain.Interval;
import io.github.viniciusclemos.booking.domain.ServiceOffering;
import io.github.viniciusclemos.booking.domain.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static io.github.viniciusclemos.booking.scheduling.BookingRulesTest.BEARD;
import static io.github.viniciusclemos.booking.scheduling.BookingRulesTest.HAIRCUT;
import static io.github.viniciusclemos.booking.scheduling.BookingRulesTest.NOW;
import static io.github.viniciusclemos.booking.scheduling.BookingRulesTest.TUESDAY_3;
import static io.github.viniciusclemos.booking.scheduling.BookingRulesTest.barbershop;
import static io.github.viniciusclemos.booking.scheduling.BookingRulesTest.tuesday;
import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityTest {

    static List<String> slots(Calendar calendar, ServiceOffering service, LocalDate date) {
        return Availability.freeSlots(calendar, service, date, NOW).stream().map(LocalTime::toString).toList();
    }

    @Test
    void wholeDayOnAHalfHourGrid() {
        Calendar calendar = barbershop().withPolicy(
                new BookingPolicy(Duration.ofHours(2), Duration.ofDays(60), Duration.ofMinutes(30)));
        assertThat(slots(calendar, HAIRCUT, TUESDAY_3)).containsExactly(
                "09:00", "09:30", "10:00", "10:30", "11:00", "11:30",
                "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00", "16:30", "17:00", "17:30");
    }

    @Test
    void bookingsAndBuffersTakeTheirSlotsAway() {
        Calendar calendar = barbershop().withBooking(new Booking(HAIRCUT, tuesday("10:00")));
        List<String> free = slots(calendar, BEARD, TUESDAY_3);
        // beard is 20 min: 09:45 would end at 10:05; 10:00-10:40 is taken
        assertThat(free).contains("09:30", "10:45").doesNotContain("09:45", "10:00", "10:15", "10:30");
    }

    @Test
    void everyOfferedSlotIsAccepted() {
        Calendar calendar = barbershop()
                .withBooking(new Booking(HAIRCUT, tuesday("09:30")))
                .withBooking(new Booking(BEARD, tuesday("15:00")))
                .withTimeOff(new Interval(tuesday("16:00"), tuesday("17:00")));
        for (LocalTime time : Availability.freeSlots(calendar, HAIRCUT, TUESDAY_3, NOW)) {
            assertThat(BookingRules.check(calendar, HAIRCUT, TUESDAY_3.atTime(time), NOW))
                    .as("slot %s", time).isEmpty();
        }
    }

    @Test
    void todayOnlyAfterTheNotice() {
        // it's 07:00 on Monday, so the first slot is 09:00
        assertThat(slots(barbershop(), BEARD, NOW.toLocalDate())).first().isEqualTo("09:00");
        LocalDateTime noon = NOW.withHour(12);
        assertThat(Availability.freeSlots(barbershop(), BEARD, NOW.toLocalDate(), noon))
                .first().isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    void nextFreeSkipsFullAndClosedDays() {
        // Saturday 07/11 after 11:00, the rest of Saturday is too short and Sunday is closed
        LocalDateTime saturday = LocalDateTime.of(2026, 11, 7, 11, 0);
        Calendar calendar = barbershop();
        calendar.schedule().onDate(LocalDate.of(2026, 11, 9)); // and Monday is a holiday
        assertThat(Availability.nextFree(calendar, HAIRCUT, saturday))
                .contains(LocalDateTime.of(2026, 11, 10, 9, 0));
    }

    @Test
    void lunchIsNeverOffered() {
        assertThat(slots(barbershop(), HAIRCUT, TUESDAY_3))
                .noneMatch(t -> t.compareTo("11:30") > 0 && t.compareTo("13:00") < 0);
    }

    @Test
    void timeRangeHelper() {
        assertThat(TimeRange.of("09:00", "12:00").overlaps(TimeRange.of("12:00", "13:00"))).isFalse();
    }
}
