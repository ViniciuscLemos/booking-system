package io.github.viniciusclemos.booking.auth;

import io.github.viniciusclemos.booking.TestDatabase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "booking.owner.name=Rui",
        "booking.owner.email=rui@northside.test",
        "booking.owner.password=correct-horse-battery",
})
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class AuthTest {

    static final String OWNER = "rui@northside.test";
    static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired StaffStore staff;
    @Autowired PasswordEncoder encoder;

    ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    // the token the way the page gets it: from the XSRF-TOKEN cookie, sent back in the header.
    // (SecurityMockMvcRequestPostProcessors.csrf() would swap the token store for the whole
    // context, and then the cookie stops going out in the other tests)
    RequestPostProcessor xsrf() throws Exception {
        Cookie cookie = mvc.perform(get("/api/services")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-XSRF-TOKEN", cookie.getValue());
            return request;
        };
    }

    MockHttpSession loggedIn() throws Exception {
        return (MockHttpSession) login(OWNER, PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void theOwnerComesFromTheSettings() {
        assertThat(staff.findByEmail("RUI@northside.test")).get()
                .satisfies(s -> {
                    assertThat(s.name()).isEqualTo("Rui");
                    // stored as a bcrypt hash, never as the password itself
                    assertThat(s.passwordHash()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
                });
    }

    @Test
    void adminNeedsALogin() throws Exception {
        mvc.perform(get("/api/admin/services")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        // the client side stays open
        mvc.perform(get("/api/services")).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        String wrong = login(OWNER, "nope-nope-nope").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknown = login("nobody@northside.test", "nope-nope-nope").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(wrong).isEqualTo(unknown).contains("Wrong e-mail or password.");
    }

    @Test
    void loggedInStaffUsesTheAdminSide() throws Exception {
        MockHttpSession session = loggedIn();
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.name").value("Rui"));
        mvc.perform(get("/api/admin/services").session(session)).andExpect(status().isOk());
        // e-mail in any case works
        login("  Rui@Northside.TEST ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void changesNeedTheCsrfToken() throws Exception {
        MockHttpSession session = loggedIn();
        String service = "{\"name\":\"Shave\",\"durationMin\":20,\"priceCents\":2500}";
        mvc.perform(post("/api/admin/services").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(service))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/services").session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(service))
                .andExpect(status().isCreated());
    }

    @Test
    void theTokenCookieGoesOutForThePage() throws Exception {
        mvc.perform(get("/api/services")).andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = loggedIn();
        mvc.perform(post("/api/auth/logout").session(session).with(xsrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/services").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void fiveWrongPasswordsLockTheEmailForAWhile() throws Exception {
        String email = "bia@northside.test";
        if (staff.findByEmail(email).isEmpty()) staff.create("Bia", email, encoder.encode("the-right-one-123"));

        for (int i = 0; i < LoginAttempts.MAX_FAILURES; i++) {
            login(email, "guess-" + i).andExpect(status().isUnauthorized());
        }
        // even the right password waits now (the test clock is stopped, so 15 minutes)
        login(email, "the-right-one-123").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too many wrong passwords. Try again in 15 minutes."));
        // other people aren't affected
        login(OWNER, PASSWORD).andExpect(status().isOk());
    }
}
