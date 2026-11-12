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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
        db.sql("TRUNCATE booking, service, working_hours, date_override, time_off, professional, professional_service, professional_hours RESTART IDENTITY CASCADE")
                .update();
        // one barber unless a test adds more, like a place that just opened
        db.sql("INSERT INTO professional (name) VALUES ('Rui')").update();
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
    void specialDatesAndTimeOffCanBeListedAndUndone() throws Exception {
        send(put("/api/admin/dates/2026-12-24"), "[{\"start\":\"09:00\",\"end\":\"13:00\"}]");
        send(put("/api/admin/dates/2026-12-25"), "[]");
        send(post("/api/admin/time-off"), """
                {"starts":"2026-11-10T14:00","ends":"2026-11-10T15:30","reason":"dentist"}""");
        // already over, so it doesn't show
        send(post("/api/admin/time-off"), """
                {"starts":"2026-10-01T09:00","ends":"2026-10-01T10:00","reason":"old"}""");

        send(get("/api/admin/exceptions"), "")
                .andExpect(jsonPath("$.dates[0].date").value("2026-12-24"))
                .andExpect(jsonPath("$.dates[0].closed").value(false))
                .andExpect(jsonPath("$.dates[1].closed").value(true))
                .andExpect(jsonPath("$.timeOff", hasSize(1)))
                .andExpect(jsonPath("$.timeOff[0].reason").value("dentist"));

        // the agenda's view of a day: Tuesday's usual hours and the dentist in the middle
        send(get("/api/admin/day").param("date", "2026-11-10"), "")
                .andExpect(jsonPath("$.ranges", hasSize(2)))
                .andExpect(jsonPath("$.timeOff[0].reason").value("dentist"));

        send(delete("/api/admin/dates/2026-12-25"), "").andExpect(status().isNoContent());
        send(delete("/api/admin/dates/2026-12-25"), "").andExpect(status().isNotFound());
        send(delete("/api/admin/time-off/1"), "").andExpect(status().isNoContent());
        send(get("/api/admin/exceptions"), "")
                .andExpect(jsonPath("$.dates", hasSize(1)))
                .andExpect(jsonPath("$.timeOff", hasSize(0)));
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
        calendars.insertBooking(haircut, 1, "Ana", "1", ten, ten.plusMinutes(30), ten.plusMinutes(40));
        assertThatThrownBy(() -> calendars.insertBooking(haircut, 1, "Bia", "2", ten.plusMinutes(35),
                ten.plusMinutes(65), ten.plusMinutes(75)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("booking_no_overlap");
        // back to back is fine: [10:00, 10:40) and [10:40, ...)
        calendars.insertBooking(haircut, 1, "Caio", "3", ten.plusMinutes(40), ten.plusMinutes(70), ten.plusMinutes(80));
        // and a cancelled booking frees its time
        db.sql("UPDATE booking SET status = 'CANCELLED' WHERE client_name = 'Ana'").update();
        calendars.insertBooking(haircut, 1, "Duda", "4", ten, ten.plusMinutes(30), ten.plusMinutes(40));
        // the same time with someone else was never a problem
        db.sql("INSERT INTO professional (name) VALUES ('Bia')").update();
        calendars.insertBooking(haircut, 2, "Eva", "5", ten, ten.plusMinutes(30), ten.plusMinutes(40));
    }

    ResultActions bookWith(String start, Long professionalId, String client) throws Exception {
        return send(post("/api/bookings"), """
                {"serviceId":%d,"start":"%s","clientName":"%s","clientPhone":"(11) 98888-7777","professionalId":%s}"""
                .formatted(haircut, start, client, professionalId));
    }

    @Test
    void twoBarbersTakeTwoClientsAtTheSameTime() throws Exception {
        send(post("/api/admin/professionals"), "{\"name\":\"Bia\"}").andExpect(status().isCreated());

        bookWith("2026-11-03T10:00", 1L, "Ana").andExpect(status().isCreated())
                .andExpect(jsonPath("$.professional").value("Rui"));
        // Rui is taken at 10:00, so "anyone" goes to Bia
        bookWith("2026-11-03T10:00", null, "Duda").andExpect(status().isCreated())
                .andExpect(jsonPath("$.professional").value("Bia"));
        // now nobody is free at 10:00, with or without a name
        bookWith("2026-11-03T10:00", null, "Caio").andExpect(status().isConflict());
        bookWith("2026-11-03T10:00", 2L, "Caio").andExpect(status().isConflict());

        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03"))
                .andExpect(jsonPath("$.slots", not(hasItem("10:00:00"))));
    }

    @Test
    void onlyWhoDoesTheServiceIsOffered() throws Exception {
        send(post("/api/admin/professionals"), "{\"name\":\"Bia\"}").andExpect(status().isCreated());
        send(post("/api/admin/services"), """
                {"name":"Hair coloring","durationMin":60,"priceCents":9000}""").andExpect(status().isCreated());
        // Bia only colors, Rui only cuts
        send(put("/api/admin/professionals/2/services"), "[2]").andExpect(status().isOk());
        send(put("/api/admin/professionals/1/services"), "[1]").andExpect(status().isOk());

        mvc.perform(get("/api/business"))
                .andExpect(jsonPath("$.professionals[1].services", contains(2)));
        // a haircut with Bia: she doesn't do it
        bookWith("2026-11-03T10:00", 2L, "Ana").andExpect(status().isNotFound());
        // coloring with anyone goes to her, the only one who does it
        send(post("/api/bookings"), """
                {"serviceId":2,"start":"2026-11-03T10:00","clientName":"Ana","clientPhone":"(11) 98888-7777"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.professional").value("Bia"));
        // and once she's booked at 10:00, nobody else is offered for coloring then
        mvc.perform(get("/api/availability").param("serviceId", "2").param("date", "2026-11-03"))
                .andExpect(jsonPath("$.slots", not(hasItem("10:00:00"))));
        // back to "all of them"
        send(put("/api/admin/professionals/2/services"), "[]").andExpect(status().isOk());
        bookWith("2026-11-03T11:00", 2L, "Duda").andExpect(status().isCreated());
    }

    @Test
    void someoneWithTheirOwnHours() throws Exception {
        send(post("/api/admin/professionals"), "{\"name\":\"Bia\"}").andExpect(status().isCreated());
        // Bia only works Tuesday afternoons
        send(put("/api/admin/professionals/2/hours"), """
                {"TUESDAY": [{"start":"13:00","end":"18:00"}]}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.TUESDAY", hasSize(1)));

        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03").param("professionalId", "2"))
                .andExpect(jsonPath("$.slots", not(hasItem("10:00:00"))))
                .andExpect(jsonPath("$.slots", hasItem("15:00:00")));
        bookWith("2026-11-03T10:00", 2L, "Ana").andExpect(status().isConflict());
        // on a Monday she's off, so "anyone" at 15:00 can only be Rui
        bookWith("2026-11-02T15:00", null, "Ana").andExpect(status().isCreated())
                .andExpect(jsonPath("$.professional").value("Rui"));
        bookWith("2026-11-02T15:00", null, "Duda").andExpect(status().isConflict());

        mvc.perform(get("/api/admin/professionals").with(user("owner@northside.test")))
                .andExpect(jsonPath("$[0].hours").doesNotExist())
                .andExpect(jsonPath("$[1].hours.TUESDAY[0].start").value("13:00:00"));
        // overlapping ranges are refused, and an empty week gives her the place's hours back
        send(put("/api/admin/professionals/2/hours"), """
                {"TUESDAY": [{"start":"09:00","end":"12:00"},{"start":"11:00","end":"14:00"}]}""")
                .andExpect(status().isBadRequest());
        send(put("/api/admin/professionals/2/hours"), "{}").andExpect(status().isOk());
        bookWith("2026-11-03T10:00", 2L, "Ana").andExpect(status().isCreated());
    }

    @Test
    void anyoneSpreadsTheDayAndAskingForSomeoneShowsOnlyTheirTimes() throws Exception {
        send(post("/api/admin/professionals"), "{\"name\":\"Bia\"}").andExpect(status().isCreated());
        // the first goes to Rui (nobody has anything yet), the next one to Bia, who has less
        bookWith("2026-11-03T09:00", null, "Ana").andExpect(jsonPath("$.professional").value("Rui"));
        bookWith("2026-11-03T14:00", null, "Duda").andExpect(jsonPath("$.professional").value("Bia"));

        // Bia away in the afternoon: her own times go, "anyone" still has Rui's
        send(post("/api/admin/time-off"), """
                {"starts":"2026-11-03T13:00","ends":"2026-11-03T18:00","reason":"Dentist","professionalId":2}""")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03").param("professionalId", "2"))
                .andExpect(jsonPath("$.slots", not(hasItem("15:00:00"))))
                .andExpect(jsonPath("$.slots", hasItem("10:00:00")));
        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03"))
                .andExpect(jsonPath("$.slots", hasItem("15:00:00")));

        // someone who left isn't offered anymore, but their old bookings stay
        send(put("/api/admin/professionals/2"), "{\"name\":\"Bia\",\"active\":false}").andExpect(status().isOk());
        mvc.perform(get("/api/business")).andExpect(jsonPath("$.professionals", hasSize(1)));
        bookWith("2026-11-03T10:00", 2L, "Caio").andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/bookings").param("date", "2026-11-03").with(user("owner@northside.test")))
                .andExpect(jsonPath("$", hasSize(2)));
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
        String token = token(book("2026-11-03T10:00"));
        mvc.perform(get("/api/bookings/by-token/" + token))
                .andExpect(jsonPath("$.clientName").value("Ana"))
                .andExpect(jsonPath("$.clientPhone").doesNotExist())
                .andExpect(jsonPath("$.cancelToken").doesNotExist());
        // no reading bookings by id, they're easy to guess
        mvc.perform(get("/api/bookings/1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/by-token/not-a-real-token")).andExpect(status().isNotFound());
    }

    String token(ResultActions created) throws Exception {
        String json = created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.cancelToken");
    }

    @Test
    void theClientCancelsWithTheLinkAndTheTimeIsFreeAgain() throws Exception {
        ResultActions created = book("2026-11-03T10:00");
        created.andExpect(jsonPath("$.canCancel").value(true))
                // 3 hours before
                .andExpect(jsonPath("$.cancelUntil").value("2026-11-03T07:00:00"));
        String token = token(created);
        // only the hash is stored
        assertThat(db.sql("SELECT cancel_token_hash FROM booking").query(String.class).single())
                .hasSize(64).isNotEqualTo(token);

        mvc.perform(post("/api/bookings/by-token/" + token + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.canCancel").value(false));
        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03"))
                .andExpect(jsonPath("$.slots", hasItem("10:00:00")));
        // and the same time can be booked again
        book("2026-11-03T10:00").andExpect(status().isCreated());

        mvc.perform(post("/api/bookings/by-token/" + token + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This booking was already cancelled."));
    }

    @Test
    void theClientMovesTheBookingWithTheSameLink() throws Exception {
        String token = token(book("2026-11-03T10:00"));
        book("2026-11-03T14:00").andExpect(status().isCreated());
        // half an hour later overlaps its own old time, and that's fine
        send(post("/api/bookings/by-token/" + token + "/move"), "{\"start\":\"2026-11-03T10:30\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.starts").value("2026-11-03T10:30:00"))
                .andExpect(jsonPath("$.id").value(1));
        mvc.perform(get("/api/availability").param("serviceId", "1").param("date", "2026-11-03"))
                .andExpect(jsonPath("$.slots", hasItem("09:30:00")))
                .andExpect(jsonPath("$.slots", not(hasItem("10:30:00"))));
        // someone else's time, or a closed one, is refused and nothing changes
        send(post("/api/bookings/by-token/" + token + "/move"), "{\"start\":\"2026-11-03T14:00\"}")
                .andExpect(status().isConflict());
        send(post("/api/bookings/by-token/" + token + "/move"), "{\"start\":\"2026-11-03T12:15\"}")
                .andExpect(status().isConflict());
        mvc.perform(get("/api/bookings/by-token/" + token)).andExpect(jsonPath("$.starts").value("2026-11-03T10:30:00"));
        // too close to the new time: has to call, like cancelling
        String late = token(book("2026-11-02T09:30"));
        send(post("/api/bookings/by-token/" + late + "/move"), "{\"start\":\"2026-11-03T16:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("give us a call")));
    }

    @Test
    void aFullDayHasAWaitingListTheStaffSees() throws Exception {
        String join = """
                {"serviceId":%d,"date":"%s","clientName":"%s","clientPhone":"%s"}""";
        send(post("/api/waiting"), join.formatted(haircut, "2026-11-03", "Bruno", "(11) 97777-1111"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.place").value(1));
        send(post("/api/waiting"), join.formatted(haircut, "2026-11-03", "Carla", "(11) 96666-2222"))
                .andExpect(jsonPath("$.place").value(2));
        // asking again keeps the first place instead of going to the end of the line
        send(post("/api/waiting"), join.formatted(haircut, "2026-11-03", "Bruno", "(11) 97777-1111"))
                .andExpect(jsonPath("$.place").value(1));
        // a past day, a day too far ahead, a bad phone or a service that doesn't exist
        send(post("/api/waiting"), join.formatted(haircut, "2026-10-01", "Dani", "(11) 95555-3333")).andExpect(status().isBadRequest());
        send(post("/api/waiting"), join.formatted(haircut, "2027-06-01", "Dani", "(11) 95555-3333")).andExpect(status().isBadRequest());
        send(post("/api/waiting"), join.formatted(haircut, "2026-11-03", "Dani", "call me")).andExpect(status().isBadRequest());
        send(post("/api/waiting"), join.formatted(99, "2026-11-03", "Dani", "(11) 95555-3333")).andExpect(status().isNotFound());

        // the list is for the staff only
        mvc.perform(get("/api/admin/waiting").param("date", "2026-11-03")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/waiting").param("date", "2026-11-03").with(user("owner@northside.test")))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].clientName").value("Bruno"))
                .andExpect(jsonPath("$[0].service").value("Haircut"))
                .andExpect(jsonPath("$[1].clientPhone").value("(11) 96666-2222"));
        long first = ((Number) com.jayway.jsonpath.JsonPath.read(mvc.perform(get("/api/admin/waiting").param("date", "2026-11-03")
                .with(user("owner@northside.test"))).andReturn().getResponse().getContentAsString(), "$[0].id")).longValue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/waiting/" + first)
                .with(user("owner@northside.test")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/waiting").param("date", "2026-11-03").with(user("owner@northside.test")))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void theBookingAsACalendarFile() throws Exception {
        String token = token(book("2026-11-03T10:00"));
        String ics = mvc.perform(get("/api/bookings/by-token/" + token + "/calendar.ics"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", org.hamcrest.Matchers.startsWith("text/calendar")))
                .andReturn().getResponse().getContentAsString();
        // 10:00 in São Paulo is 13:00 UTC; 30 minutes of haircut
        assertThat(ics).contains("BEGIN:VEVENT", "DTSTART:20261103T130000Z", "DTEND:20261103T133000Z",
                "SUMMARY:Haircut with Rui at Northside Barbers", "TRIGGER:-P1D", "STATUS:CONFIRMED");
        assertThat(ics).contains("\r\n").doesNotContain("98888");
        mvc.perform(get("/api/bookings/by-token/nope/calendar.ics")).andExpect(status().isNotFound());
    }

    @Test
    void tooCloseToTheTimeTheClientHasToCall() throws Exception {
        // it's Monday 07:00 and the booking is at 09:30: the 3 hours ended at 06:30
        String token = token(book("2026-11-02T09:30"));
        mvc.perform(get("/api/bookings/by-token/" + token)).andExpect(jsonPath("$.canCancel").value(false));
        mvc.perform(post("/api/bookings/by-token/" + token + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("give us a call")));

        // the staff still can, and the agenda shows who cancelled
        send(post("/api/admin/bookings/1/cancel"), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledBy").value("STAFF"));
        send(get("/api/admin/bookings").param("date", "2026-11-02"), "")
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
    }
}
