package io.github.viniciusclemos.booking.api;

import io.github.viniciusclemos.booking.TestDatabase;
import io.github.viniciusclemos.booking.scheduling.BookingRefused;
import io.github.viniciusclemos.booking.scheduling.SchedulingService;
import io.github.viniciusclemos.booking.scheduling.Violation;
import io.github.viniciusclemos.booking.store.CalendarStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired SchedulingService scheduling;
    @Autowired CalendarStore calendars;

    long haircut;

    @BeforeEach
    void barbershop() throws Exception {
        db.sql("TRUNCATE booking, service, working_hours, date_override, time_off RESTART IDENTITY CASCADE").update();
        send(put("/api/admin/hours"), """
                {"MONDAY": [{"start":"09:00","end":"12:00"},{"start":"13:00","end":"18:00"}],
                 "TUESDAY": [{"start":"09:00","end":"12:00"},{"start":"13:00","end":"18:00"}],
                 "SATURDAY": [{"start":"09:00","end":"13:00"}]}""").andExpect(status().isOk());
        send(post("/api/admin/services"), """
                {"name":"Haircut","durationMin":30,"bufferMin":10,"priceCents":4500}""")
                .andExpect(status().isCreated());
        haircut = 1;
    }

    // the admin calls go as a logged in staff member with the CSRF token; the login itself is tested in AuthTest
    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String json)
            throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json).with(r -> {
            if (!r.getRequestURI().startsWith("/api/admin")) return r;
            return csrf().postProcessRequest(user("owner@northside.test").postProcessRequest(r));
        }));
    }

    ResultActions book(String start) throws Exception {
        return send(post("/api/bookings"), """
                {"serviceId":%d,"start":"%s","clientName":"Ana","clientPhone":"(11) 98888-7777"}"""
                .formatted(haircut, start));
    }

    @Test
    void listsActiveServices() throws Exception {
        send(post("/api/admin/services"), """
                {"name":"Old one","durationMin":20,"priceCents":1000}""");
        send(put("/api/admin/services/2"), """
                {"name":"Old one","durationMin":20,"priceCents":1000,"active":false}""").andExpect(status().isOk());

        mvc.perform(get("/api/services"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Haircut"))
                .andExpect(jsonPath("$[0].durationMin").value(30));
    }

    @Test
    void slotsForADay() throws Exception {
        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0]").value("09:00:00"))
                .andExpect(jsonPath("$.slots", hasItem("13:00:00")))
                .andExpect(jsonPath("$.slots", not(hasItem("11:45:00"))));
    }

    @Test
    void bookingTakesTheSlotAway() throws Exception {
        book("2026-11-03T10:00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.service").value("Haircut"))
                .andExpect(jsonPath("$.ends").value("2026-11-03T10:30:00"));

        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03"))
                .andExpect(jsonPath("$.slots", not(hasItem("10:00:00"))))
                .andExpect(jsonPath("$.slots", not(hasItem("10:30:00"))))
                .andExpect(jsonPath("$.slots", hasItem("10:45:00")));
    }

    @Test
    void refusedTimesSayWhy() throws Exception {
        book("2026-11-03T10:00");
        book("2026-11-03T10:15").andExpect(status().isConflict())
                .andExpect(jsonPath("$.problems[*].code", contains("CLASHES_WITH_BOOKING")));
        book("2026-11-03T11:45").andExpect(status().isConflict())
                .andExpect(jsonPath("$.problems[*].code", contains("CROSSES_BREAK")));
        book("2026-11-08T10:00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.problems[*].code", contains("CLOSED_THAT_DAY")));
        book("2026-11-02T08:00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.problems[*].code", hasItem("TOO_SOON")));
    }

    @Test
    void badInputIs400() throws Exception {
        send(post("/api/bookings"), """
                {"serviceId":1,"start":"2026-11-03T10:00","clientName":"","clientPhone":"abc"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.problems", hasSize(2)));
        send(post("/api/bookings"), "{\"serviceId\":1,\"start\":\"tomorrow\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/availability").param("serviceId", "99").param("date", "2026-11-03"))
                .andExpect(status().isNotFound());
        send(put("/api/admin/hours"), """
                {"MONDAY": [{"start":"09:00","end":"12:00"},{"start":"11:00","end":"14:00"}]}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void specialDateAndTimeOff() throws Exception {
        send(put("/api/admin/dates/2026-11-03"), "[]").andExpect(status().isOk());
        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03"))
                .andExpect(jsonPath("$.slots", hasSize(0)));

        send(post("/api/admin/time-off"), """
                {"starts":"2026-11-07T09:00","ends":"2026-11-07T11:00","reason":"dentist"}""")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-07"))
                .andExpect(jsonPath("$.slots[0]").value("11:00:00"));
    }

    @Test
    void daysOverviewInOneGo() throws Exception {
        mvc.perform(get("/api/availability/days").param("serviceId", "1").param("from", "2026-11-02").param("count", "7"))
                .andExpect(jsonPath("$", hasSize(7)))
                // Monday at 07:00 with 2h of notice: first slot 09:00
                .andExpect(jsonPath("$[0].first").value("09:00:00"))
                // Wednesday has no hours in this setup
                .andExpect(jsonPath("$[2].open").value(false))
                .andExpect(jsonPath("$[6].free").value(0));
    }

    @Test
    void onlyOneOfManySimultaneousRequestsWins() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 11, 3, 15, 0);
        int people = 8;
        ExecutorService pool = Executors.newFixedThreadPool(people);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            String name = "Client " + i;
            Callable<Boolean> attempt = () -> {
                go.await();
                try {
                    scheduling.book(haircut, start, name, "11999990000");
                    return true;
                } catch (BookingRefused e) {
                    assertThat(e.violations()).containsExactly(Violation.CLASHES_WITH_BOOKING);
                    return false;
                }
            };
            results.add(pool.submit(attempt));
        }
        go.countDown();
        int won = 0;
        for (Future<Boolean> r : results) if (r.get()) won++;
        pool.shutdown();

        assertThat(won).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM booking").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesOverlapsEvenWithoutTheRules() {
        LocalDateTime ten = LocalDateTime.of(2026, 11, 3, 10, 0);
        calendars.insertBooking(haircut, "Ana", "1", ten, ten.plusMinutes(30), ten.plusMinutes(40));
        assertThatThrownBy(() -> calendars.insertBooking(haircut, "Bia", "2", ten.plusMinutes(35),
                ten.plusMinutes(65), ten.plusMinutes(75)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("booking_no_overlap");
        // back to back is fine: [10:00, 10:40) and [10:40, ...)
        calendars.insertBooking(haircut, "Caio", "3", ten.plusMinutes(40), ten.plusMinutes(70), ten.plusMinutes(80));
        // and a cancelled booking frees its time
        db.sql("UPDATE booking SET status = 'CANCELLED' WHERE client_name = 'Ana'").update();
        calendars.insertBooking(haircut, "Duda", "4", ten, ten.plusMinutes(30), ten.plusMinutes(40));
    }

    @Test
    void servesTheBookingPage() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/business"))
                .andExpect(jsonPath("$.name").value("Northside Barbers"))
                .andExpect(jsonPath("$.hours.MONDAY", hasSize(2)))
                .andExpect(jsonPath("$.minNoticeMinutes").value(120));
    }

    @Test
    void publicBookingViewHidesThePhone() throws Exception {
        book("2026-11-03T10:00");
        mvc.perform(get("/api/bookings/1"))
                .andExpect(jsonPath("$.clientName").value("Ana"))
                .andExpect(jsonPath("$.clientPhone").doesNotExist());
        mvc.perform(get("/api/bookings/42")).andExpect(status().isNotFound());
    }
}
