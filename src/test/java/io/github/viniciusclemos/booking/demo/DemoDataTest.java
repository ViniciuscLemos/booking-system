package io.github.viniciusclemos.booking.demo;

import io.github.viniciusclemos.booking.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "booking.demo=true")
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class DemoDataTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired DemoData demo;

    @Test
    void theDemoIsReadyToUse() throws Exception {
        // the embedded database is shared with the other tests, so start this one clean
        db.sql("TRUNCATE booking, service, working_hours, date_override, time_off, staff, professional RESTART IDENTITY CASCADE")
                .update();
        // the one the migration leaves behind
        db.sql("INSERT INTO professional (name) VALUES ('Me')").update();
        demo.run(null);
        mvc.perform(get("/api/business")).andExpect(jsonPath("$.professionals", hasSize(3)))
                .andExpect(jsonPath("$.professionals[0].name").value("Rui"));

        mvc.perform(get("/api/services")).andExpect(jsonPath("$", hasSize(5)));
        mvc.perform(get("/api/business"))
                .andExpect(jsonPath("$.demoLogin.email").value(DemoData.EMAIL))
                .andExpect(jsonPath("$.demoLogin.password").value(DemoData.PASSWORD));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(DemoData.EMAIL, DemoData.PASSWORD)))
                .andExpect(status().isOk());

        int bookings = db.sql("SELECT count(*) FROM booking").query(Integer.class).single();
        assertThat(bookings).isGreaterThan(10);
        // the nightly top-up only fills quiet days, and never on top of another booking
        demo.topUp();
        assertThat(db.sql("SELECT count(*) FROM booking").query(Integer.class).single()).isGreaterThanOrEqualTo(bookings);
        assertThat(db.sql("""
                SELECT count(*) FROM booking a JOIN booking b ON a.id < b.id
                WHERE a.professional_id = b.professional_id
                  AND tsrange(a.starts, a.blocked_until) && tsrange(b.starts, b.blocked_until)""")
                .query(Integer.class).single()).isZero();
    }
}
